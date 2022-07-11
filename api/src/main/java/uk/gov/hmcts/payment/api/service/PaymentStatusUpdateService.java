package uk.gov.hmcts.payment.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import uk.gov.hmcts.payment.api.dto.PaymentStatusBouncedChequeDto;
import uk.gov.hmcts.payment.api.dto.PaymentStatusChargebackDto;
import uk.gov.hmcts.payment.api.dto.PaymentStatusUpdateSecond;
import uk.gov.hmcts.payment.api.model.Payment;
import uk.gov.hmcts.payment.api.model.PaymentFailures;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentStatusUpdateService {

    PaymentFailures insertBounceChequePaymentFailure(PaymentStatusBouncedChequeDto paymentStatusBouncedChequeDto);
    Optional<PaymentFailures> searchFailureReference(String failureReference);

    void sendFailureMessageToServiceTopic(Payment payment, PaymentFailures paymentFailure) throws JsonProcessingException;

    boolean cancelFailurePaymentRefund(String paymentReference);

    PaymentFailures insertChargebackPaymentFailure(PaymentStatusChargebackDto paymentStatusChargebackDto);

    List<PaymentFailures> searchPaymentFailure(String failureReference);
    void deleteByFailureReference(String failureReference);

    PaymentFailures updatePaymentFailure(PaymentFailures paymentFailures, PaymentStatusUpdateSecond paymentStatusUpdateSecond);
}
