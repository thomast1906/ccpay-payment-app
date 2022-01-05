
package uk.gov.hmcts.payment.api.service;

import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.payment.api.domain.model.Roles;
import uk.gov.hmcts.payment.api.model.Payment;
import uk.gov.hmcts.payment.api.model.PaymentFee;

public interface RefundRemissionEnableService {

    Boolean returnRefundEligible(Payment payment);
    Boolean returnRemissionEligible(PaymentFee fee);
    Roles getRoles(MultiValueMap<String, String> headers);

}

