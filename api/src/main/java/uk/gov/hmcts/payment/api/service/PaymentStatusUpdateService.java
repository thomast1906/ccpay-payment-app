package uk.gov.hmcts.payment.api.service;

import uk.gov.hmcts.payment.api.dto.PaymentStatusBouncedChequeDto;
import uk.gov.hmcts.payment.api.dto.PaymentStatusChargebackDto;
import uk.gov.hmcts.payment.api.model.PaymentFailures;

import java.util.List;
import java.util.Optional;

public interface PaymentStatusUpdateService {

    PaymentFailures insertBounceChequePaymentFailure(PaymentStatusBouncedChequeDto paymentStatusBouncedChequeDto);
    Optional<PaymentFailures> searchFailureReference(String failureReference);

    boolean cancelFailurePaymentRefund(String paymentReference);

    PaymentFailures insertChargebackPaymentFailure(PaymentStatusChargebackDto paymentStatusChargebackDto);

    List<PaymentFailures> searchPaymentFailure(String failureReference);
    void deleteByFailureReference(String failureReference);
}
