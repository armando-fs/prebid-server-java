package org.prebid.server.auction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.Future;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.auction.model.AuctionParticipation;
import org.prebid.server.auction.model.BidRejectionTracker;
import org.prebid.server.auction.model.BidderRequest;
import org.prebid.server.auction.model.BidderResponse;
import org.prebid.server.auction.model.StoredResponseResult;
import org.prebid.server.auction.model.Tuple2;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderSeatBid;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.execution.timeout.Timeout;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.request.ExtImp;
import org.prebid.server.proto.openrtb.ext.request.ExtImpPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtStoredAuctionResponse;
import org.prebid.server.proto.openrtb.ext.request.ExtStoredBidResponse;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.settings.ApplicationSettings;
import org.prebid.server.settings.model.StoredResponseDataResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves stored response data retrieving and BidderResponse merging processes.
 */
public class StoredResponseProcessor {

    private static final String PREBID_EXT = "prebid";
    private static final String DEFAULT_BID_CURRENCY = "USD";
    private static final String PBS_IMPID_MACRO = "##PBSIMPID##";

    private static final TypeReference<List<SeatBid>> SEATBID_LIST_TYPE =
            new TypeReference<>() {
            };

    private final ApplicationSettings applicationSettings;
    private final JacksonMapper mapper;

    public StoredResponseProcessor(ApplicationSettings applicationSettings,
                                   JacksonMapper mapper) {

        this.applicationSettings = Objects.requireNonNull(applicationSettings);
        this.mapper = Objects.requireNonNull(mapper);
    }

    Future<StoredResponseResult> getStoredResponseResult(List<Imp> imps, Timeout timeout) {
        final Map<String, ExtImpPrebid> impExtPrebids = getImpsExtPrebid(imps);
        final Map<String, StoredResponse> impIdsToStoredResponses = getAuctionStoredResponses(impExtPrebids);
        final List<Imp> requiredRequestImps = excludeStoredAuctionResponseImps(imps, impIdsToStoredResponses);

        final Map<String, Map<String, StoredResponse.StoredResponseId>> impToBidderToStoredBidResponseId =
                getStoredBidResponses(impExtPrebids, requiredRequestImps);

        final Set<StoredResponse> storedResponses = new HashSet<>(impIdsToStoredResponses.values());

        impToBidderToStoredBidResponseId.values()
                .forEach(bidderToStoredResponse -> storedResponses.addAll(bidderToStoredResponse.values()));

        if (storedResponses.isEmpty()) {
            return Future.succeededFuture(
                    StoredResponseResult.of(imps, Collections.emptyList(), Collections.emptyMap()));
        }

        return getStoredResponses(storedResponses, timeout)
                .recover(exception -> Future.failedFuture(new InvalidRequestException(
                        "Stored response fetching failed with reason: " + exception.getMessage())))
                .map(storedResponseDataResult -> StoredResponseResult.of(
                        requiredRequestImps,
                        convertToSeatBid(storedResponseDataResult, impIdsToStoredResponses),
                        mapStoredBidResponseIdsToValues(
                                storedResponseDataResult.getIdToStoredResponses(),
                                impToBidderToStoredBidResponseId)));
    }

    Future<StoredResponseResult> getStoredResponseResult(String storedId, Timeout timeout) {
        return applicationSettings.getStoredResponses(Collections.singleton(storedId), timeout)
                .recover(exception -> Future.failedFuture(new InvalidRequestException(
                        "Stored response fetching failed with reason: " + exception.getMessage())))
                .map(storedResponseDataResult -> StoredResponseResult.of(
                        Collections.emptyList(),
                        convertToSeatBid(storedResponseDataResult),
                        Collections.emptyMap()));
    }

    private Map<String, ExtImpPrebid> getImpsExtPrebid(List<Imp> imps) {
        return imps.stream()
                .collect(Collectors.toMap(Imp::getId, imp -> getExtImp(imp.getExt(), imp.getId()).getPrebid()));
    }

    private ExtImp getExtImp(ObjectNode extImpNode, String impId) {
        try {
            return mapper.mapper().treeToValue(extImpNode, ExtImp.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException(
                    "Error decoding bidRequest.imp.ext for impId = %s : %s".formatted(impId, e.getMessage()));
        }
    }

    private Map<String, StoredResponse> getAuctionStoredResponses(Map<String, ExtImpPrebid> extImpPrebids) {
        return extImpPrebids.entrySet().stream()
                .map(impIdToExtPrebid -> Tuple2.of(
                        impIdToExtPrebid.getKey(),
                        extractAuctionStoredResponseId(impIdToExtPrebid.getValue())))
                .filter(impIdToStoredResponseId -> impIdToStoredResponseId.getRight() != null)
                .collect(Collectors.toMap(Tuple2::getLeft, Tuple2::getRight));
    }

    private StoredResponse extractAuctionStoredResponseId(ExtImpPrebid extImpPrebid) {
        final ExtStoredAuctionResponse storedAuctionResponse = extImpPrebid.getStoredAuctionResponse();
        return Optional.ofNullable(storedAuctionResponse)
                .map(ExtStoredAuctionResponse::getSeatBid)
                .<StoredResponse>map(StoredResponse.StoredResponseObject::new)
                .or(() -> Optional.ofNullable(storedAuctionResponse)
                        .map(ExtStoredAuctionResponse::getId)
                        .map(StoredResponse.StoredResponseId::new))
                .orElse(null);
    }

    private List<Imp> excludeStoredAuctionResponseImps(List<Imp> imps,
                                                       Map<String, StoredResponse> impIdToStoredResponse) {

        return imps.stream()
                .filter(imp -> !impIdToStoredResponse.containsKey(imp.getId()))
                .toList();
    }

    private Map<String, Map<String, StoredResponse.StoredResponseId>> getStoredBidResponses(
            Map<String, ExtImpPrebid> extImpPrebids,
            List<Imp> imps) {

        // PBS supports stored bid response only for requests with single impression, but it can be changed in future
        if (imps.size() != 1) {
            return Collections.emptyMap();
        }

        return extImpPrebids.entrySet().stream()
                .filter(impIdToExtPrebid ->
                        CollectionUtils.isNotEmpty(impIdToExtPrebid.getValue().getStoredBidResponse()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        impIdToStoredResponses ->
                                resolveStoredBidResponse(impIdToStoredResponses.getValue().getStoredBidResponse())));
    }

    private Map<String, StoredResponse.StoredResponseId> resolveStoredBidResponse(
            List<ExtStoredBidResponse> storedBidResponse) {

        return storedBidResponse.stream()
                .collect(Collectors.toMap(
                        ExtStoredBidResponse::getBidder,
                        extStoredBidResponse -> new StoredResponse.StoredResponseId(extStoredBidResponse.getId())));
    }

    private Future<StoredResponseDataResult> getStoredResponses(Set<StoredResponse> storedResponses, Timeout timeout) {
        return applicationSettings.getStoredResponses(
                storedResponses.stream()
                        .filter(StoredResponse.StoredResponseId.class::isInstance)
                        .map(StoredResponse.StoredResponseId.class::cast)
                        .map(StoredResponse.StoredResponseId::id)
                        .collect(Collectors.toSet()),
                timeout);
    }

    private List<SeatBid> convertToSeatBid(StoredResponseDataResult storedResponseDataResult,
                                           Map<String, StoredResponse> impIdsToStoredResponses) {

        final List<SeatBid> resolvedSeatBids = new ArrayList<>();
        final Map<String, String> idToStoredResponses = storedResponseDataResult.getIdToStoredResponses();
        for (Map.Entry<String, StoredResponse> impIdToStoredResponse : impIdsToStoredResponses.entrySet()) {
            final String impId = impIdToStoredResponse.getKey();
            final StoredResponse storedResponse = impIdToStoredResponse.getValue();
            final List<SeatBid> seatBids = resolveSeatBids(storedResponse, idToStoredResponses, impId);

            validateStoredSeatBid(seatBids);
            resolvedSeatBids.addAll(seatBids.stream()
                    .map(seatBid -> updateSeatBidBids(seatBid, impId))
                    .toList());
        }
        return mergeSameBidderSeatBid(resolvedSeatBids);
    }

    private List<SeatBid> convertToSeatBid(StoredResponseDataResult storedResponseDataResult) {
        final List<SeatBid> resolvedSeatBids = new ArrayList<>();
        final Map<String, String> idToStoredResponses = storedResponseDataResult.getIdToStoredResponses();
        for (Map.Entry<String, String> storedIdToImpId : idToStoredResponses.entrySet()) {
            final String id = storedIdToImpId.getKey();
            final String rowSeatBid = storedIdToImpId.getValue();
            if (rowSeatBid == null) {
                throw new InvalidRequestException(
                        "Failed to fetch stored auction response for storedAuctionResponse id = %s.".formatted(id));
            }
            final List<SeatBid> seatBids = parseSeatBid(id, rowSeatBid);
            validateStoredSeatBid(seatBids);
            resolvedSeatBids.addAll(seatBids);
        }
        return mergeSameBidderSeatBid(resolvedSeatBids);
    }

    private List<SeatBid> resolveSeatBids(StoredResponse storedResponse,
                                          Map<String, String> idToStoredResponses,
                                          String impId) {

        if (storedResponse instanceof StoredResponse.StoredResponseObject storedResponseObject) {
            return Collections.singletonList(storedResponseObject.seatBid());
        }

        final String storedResponseId = ((StoredResponse.StoredResponseId) storedResponse).id();
        final String rowSeatBid = idToStoredResponses.get(storedResponseId);
        if (rowSeatBid == null) {
            throw new InvalidRequestException(
                    "Failed to fetch stored auction response for impId = %s and storedAuctionResponse id = %s."
                            .formatted(impId, storedResponseId));
        }

        return parseSeatBid(storedResponseId, rowSeatBid);
    }

    private List<SeatBid> parseSeatBid(String id, String rowSeatBid) {
        try {
            return mapper.mapper().readValue(rowSeatBid, SEATBID_LIST_TYPE);
        } catch (IOException e) {
            throw new InvalidRequestException("Can't parse Json for stored response with id " + id);
        }
    }

    private void validateStoredSeatBid(List<SeatBid> seatBids) {
        for (final SeatBid seatBid : seatBids) {
            if (StringUtils.isEmpty(seatBid.getSeat())) {
                throw new InvalidRequestException("Seat can't be empty in stored response seatBid");
            }

            if (CollectionUtils.isEmpty(seatBid.getBid())) {
                throw new InvalidRequestException("There must be at least one bid in stored response seatBid");
            }
        }
    }

    private SeatBid updateSeatBidBids(SeatBid seatBid, String impId) {
        return seatBid.toBuilder().bid(updateBidsWithImpId(seatBid.getBid(), impId)).build();
    }

    private List<Bid> updateBidsWithImpId(List<Bid> bids, String impId) {
        return bids.stream().map(bid -> updateBidWithImpId(bid, impId)).toList();
    }

    private static Bid updateBidWithImpId(Bid bid, String impId) {
        return bid.toBuilder().impid(impId).build();
    }

    private List<SeatBid> mergeSameBidderSeatBid(List<SeatBid> seatBids) {
        return seatBids.stream().collect(Collectors.groupingBy(SeatBid::getSeat, Collectors.toList()))
                .entrySet().stream()
                .map(bidderToSeatBid -> makeMergedSeatBid(bidderToSeatBid.getKey(), bidderToSeatBid.getValue()))
                .toList();
    }

    private SeatBid makeMergedSeatBid(String seat, List<SeatBid> storedSeatBids) {
        return SeatBid.builder()
                .bid(storedSeatBids.stream().map(SeatBid::getBid).flatMap(List::stream).toList())
                .seat(seat)
                .ext(storedSeatBids.stream().map(SeatBid::getExt).filter(Objects::nonNull).findFirst().orElse(null))
                .build();
    }

    private Map<String, Map<String, String>> mapStoredBidResponseIdsToValues(
            Map<String, String> idToStoredResponses,
            Map<String, Map<String, StoredResponse.StoredResponseId>> impToBidderToStoredBidResponseId) {

        return impToBidderToStoredBidResponseId.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().entrySet().stream()
                                .filter(bidderToId -> idToStoredResponses.containsKey(bidderToId.getValue().id()))
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        bidderToId -> idToStoredResponses.get(bidderToId.getValue().id()),
                                        (first, second) -> second,
                                        CaseInsensitiveMap::new))));
    }

    public List<AuctionParticipation> updateStoredBidResponse(List<AuctionParticipation> auctionParticipations) {
        return auctionParticipations.stream()
                .map(StoredResponseProcessor::updateStoredBidResponse)
                .collect(Collectors.toList());
    }

    private static AuctionParticipation updateStoredBidResponse(AuctionParticipation auctionParticipation) {
        final BidderRequest bidderRequest = auctionParticipation.getBidderRequest();
        final BidRequest bidRequest = bidderRequest.getBidRequest();

        final List<Imp> imps = bidRequest.getImp();
        // Аor now, Stored Bid Response works only for bid requests with single imp
        if (imps.size() > 1 || StringUtils.isEmpty(bidderRequest.getStoredResponse())) {
            return auctionParticipation;
        }

        final BidderResponse bidderResponse = auctionParticipation.getBidderResponse();
        final BidderSeatBid initialSeatBid = bidderResponse.getSeatBid();
        final BidderSeatBid adjustedSeatBid = updateSeatBid(initialSeatBid, imps.getFirst().getId());

        return auctionParticipation.with(bidderResponse.with(adjustedSeatBid));
    }

    private static BidderSeatBid updateSeatBid(BidderSeatBid bidderSeatBid, String impId) {
        final List<BidderBid> bids = bidderSeatBid.getBids().stream()
                .map(bidderBid -> resolveBidImpId(bidderBid, impId))
                .collect(Collectors.toList());

        return bidderSeatBid.with(bids);
    }

    private static BidderBid resolveBidImpId(BidderBid bidderBid, String impId) {
        final Bid bid = bidderBid.getBid();
        final String bidImpId = bid.getImpid();
        if (!StringUtils.contains(bidImpId, PBS_IMPID_MACRO)) {
            return bidderBid;
        }

        return bidderBid.toBuilder()
                .bid(bid.toBuilder().impid(bidImpId.replace(PBS_IMPID_MACRO, impId)).build())
                .build();
    }

    List<AuctionParticipation> mergeWithBidderResponses(List<AuctionParticipation> auctionParticipations,
                                                        List<SeatBid> storedAuctionResponses,
                                                        List<Imp> imps,
                                                        Map<String, BidRejectionTracker> bidRejectionTrackers) {

        if (CollectionUtils.isEmpty(storedAuctionResponses)) {
            return auctionParticipations;
        }

        final Map<String, AuctionParticipation> bidderToAuctionParticipation = auctionParticipations.stream()
                .collect(Collectors.toMap(AuctionParticipation::getBidder, Function.identity()));
        final Map<String, SeatBid> bidderToSeatBid = storedAuctionResponses.stream()
                .collect(Collectors.toMap(SeatBid::getSeat, Function.identity()));
        final Map<String, BidType> impIdToBidType = imps.stream()
                .collect(Collectors.toMap(Imp::getId, this::resolveBidType));
        final Set<String> responseBidders = new HashSet<>(bidderToAuctionParticipation.keySet());
        responseBidders.addAll(bidderToSeatBid.keySet());

        return responseBidders.stream()
                .map(bidder -> updateBidderResponse(
                        bidderToAuctionParticipation.get(bidder),
                        bidderToSeatBid.get(bidder),
                        impIdToBidType))
                .map(auctionParticipation -> restoreStoredBidsFromRejection(bidRejectionTrackers, auctionParticipation))
                .toList();
    }

    private BidType resolveBidType(Imp imp) {
        BidType bidType = BidType.banner;
        if (imp.getBanner() != null) {
            return bidType;
        } else if (imp.getVideo() != null) {
            bidType = BidType.video;
        } else if (imp.getXNative() != null) {
            bidType = BidType.xNative;
        } else if (imp.getAudio() != null) {
            bidType = BidType.audio;
        }
        return bidType;
    }

    private AuctionParticipation updateBidderResponse(AuctionParticipation auctionParticipation,
                                                      SeatBid storedSeatBid,
                                                      Map<String, BidType> impIdToBidType) {

        if (auctionParticipation != null) {
            if (auctionParticipation.isRequestBlocked()) {
                return auctionParticipation;
            }

            final BidderResponse bidderResponse = auctionParticipation.getBidderResponse();
            final BidderSeatBid bidderSeatBid = bidderResponse.getSeatBid();
            final BidderSeatBid updatedSeatBid = storedSeatBid == null
                    ? bidderSeatBid
                    : makeBidderSeatBid(bidderSeatBid, storedSeatBid, impIdToBidType);
            final BidderResponse updatedBidderResponse = BidderResponse.of(bidderResponse.getBidder(),
                    updatedSeatBid, bidderResponse.getResponseTime());
            return auctionParticipation.with(updatedBidderResponse);
        } else {
            final String bidder = storedSeatBid != null ? storedSeatBid.getSeat() : null;
            final BidderSeatBid updatedSeatBid = makeBidderSeatBid(null, storedSeatBid, impIdToBidType);
            final BidderResponse updatedBidderResponse = BidderResponse.of(bidder, updatedSeatBid, 0);
            return AuctionParticipation.builder()
                    .bidder(bidder)
                    .bidderResponse(updatedBidderResponse)
                    .build();
        }
    }

    private BidderSeatBid makeBidderSeatBid(BidderSeatBid bidderSeatBid,
                                            SeatBid seatBid,
                                            Map<String, BidType> impIdToBidType) {

        final boolean nonNullBidderSeatBid = bidderSeatBid != null;
        final String bidCurrency = nonNullBidderSeatBid
                ? bidderSeatBid.getBids().stream()
                .map(BidderBid::getBidCurrency)
                .filter(Objects::nonNull)
                .findAny()
                .orElse(DEFAULT_BID_CURRENCY)
                : DEFAULT_BID_CURRENCY;
        final List<BidderBid> bidderBids = seatBid != null
                ? seatBid.getBid().stream()
                .map(bid -> makeBidderBid(bid, bidCurrency, seatBid.getSeat(), impIdToBidType))
                .collect(Collectors.toCollection(ArrayList::new))
                : new ArrayList<>();
        if (nonNullBidderSeatBid) {
            bidderBids.addAll(bidderSeatBid.getBids());
        }
        return nonNullBidderSeatBid
                ? bidderSeatBid.with(bidderBids)
                : BidderSeatBid.of(bidderBids);
    }

    private BidderBid makeBidderBid(Bid bid, String bidCurrency, String seat, Map<String, BidType> impIdToBidType) {
        return BidderBid.of(bid, getBidType(bid.getExt(), impIdToBidType.get(bid.getImpid())), seat, bidCurrency);
    }

    private BidType getBidType(ObjectNode bidExt, BidType bidType) {
        final ObjectNode bidExtPrebid = bidExt != null ? (ObjectNode) bidExt.get(PREBID_EXT) : null;
        final ExtBidPrebid extBidPrebid = bidExtPrebid != null ? parseExtBidPrebid(bidExtPrebid) : null;
        return extBidPrebid != null ? extBidPrebid.getType() : bidType;
    }

    private ExtBidPrebid parseExtBidPrebid(ObjectNode bidExtPrebid) {
        try {
            return mapper.mapper().treeToValue(bidExtPrebid, ExtBidPrebid.class);
        } catch (JsonProcessingException e) {
            throw new PreBidException("Error decoding stored response bid.ext.prebid");
        }
    }

    private static AuctionParticipation restoreStoredBidsFromRejection(
            Map<String, BidRejectionTracker> bidRejectionTrackers,
            AuctionParticipation auctionParticipation) {

        final BidRejectionTracker bidRejectionTracker = bidRejectionTrackers.get(auctionParticipation.getBidder());

        if (bidRejectionTracker != null) {
            Optional.ofNullable(auctionParticipation.getBidderResponse())
                    .map(BidderResponse::getSeatBid)
                    .map(BidderSeatBid::getBids)
                    .ifPresent(bidRejectionTracker::restoreFromRejection);
        }

        return auctionParticipation;
    }

    private sealed interface StoredResponse {

        record StoredResponseId(String id) implements StoredResponse {
        }

        record StoredResponseObject(SeatBid seatBid) implements StoredResponse {
        }
    }
}
