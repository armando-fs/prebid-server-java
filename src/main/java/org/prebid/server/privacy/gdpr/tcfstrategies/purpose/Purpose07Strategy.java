package org.prebid.server.privacy.gdpr.tcfstrategies.purpose;

import org.prebid.server.privacy.gdpr.model.PrivacyEnforcementAction;
import org.prebid.server.privacy.gdpr.tcfstrategies.purpose.typestrategies.BasicEnforcePurposeStrategy;
import org.prebid.server.privacy.gdpr.tcfstrategies.purpose.typestrategies.FullEnforcePurposeStrategy;
import org.prebid.server.privacy.gdpr.tcfstrategies.purpose.typestrategies.NoEnforcePurposeStrategy;
import org.prebid.server.privacy.gdpr.vendorlist.proto.PurposeCode;

public class Purpose07Strategy extends PurposeStrategy {

    public Purpose07Strategy(FullEnforcePurposeStrategy fullEnforcePurposeStrategy,
                             BasicEnforcePurposeStrategy basicEnforcePurposeStrategy,
                             NoEnforcePurposeStrategy noEnforcePurposeStrategy) {

        super(fullEnforcePurposeStrategy, basicEnforcePurposeStrategy, noEnforcePurposeStrategy);
    }

    @Override
    public void allow(PrivacyEnforcementAction privacyEnforcementAction) {
        privacyEnforcementAction.setBlockAnalyticsReport(false);
    }

    @Override
    public void allowNaturally(PrivacyEnforcementAction privacyEnforcementAction) {
        privacyEnforcementAction.setRemoveUserIds(false);
    }

    @Override
    public PurposeCode getPurpose() {
        return PurposeCode.SEVEN;
    }

}
