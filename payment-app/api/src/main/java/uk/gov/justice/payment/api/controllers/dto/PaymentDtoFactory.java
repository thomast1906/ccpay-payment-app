package uk.gov.justice.payment.api.controllers.dto;

import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.justice.payment.api.controllers.PaymentController;
import uk.gov.justice.payment.api.model.PaymentDetails;

import java.lang.reflect.Method;

import static org.springframework.hateoas.mvc.ControllerLinkBuilder.linkTo;

@Component
public class
PaymentDtoFactory {

    public PaymentDto toDto(PaymentDetails payment) {
        return PaymentDto.paymentDtoWith()
                .paymentId(payment.getPaymentId())
                .amount(payment.getAmount())
                .state(toStateDto(payment.getStatus(), payment.getFinished()))
                .description(payment.getDescription())
                .applicationReference(payment.getApplicationReference())
                .paymentReference(payment.getPaymentReference())
                .createdDate(payment.getCreatedDate())
                .links(new PaymentDto.LinksDto(
                        payment.getNextUrl() == null ? null : new PaymentDto.LinkDto(payment.getNextUrl(), "GET"),
                        payment.getCancelUrl() == null ? null : cancellationLink(payment.getPaymentId())
                ))
                .build();
    }

    private PaymentDto.StateDto toStateDto(String status, Boolean finished) {
        return new PaymentDto.StateDto(status, finished);
    }

    @SneakyThrows(NoSuchMethodException.class)
    private PaymentDto.LinkDto cancellationLink(String paymentId) {
        Method method = PaymentController.class.getMethod("cancel", String.class, String.class);
        return new PaymentDto.LinkDto(linkTo(method, paymentId).toString(), "POST");
    }

}
