package uk.gov.hmcts.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.payment.api.contract.DisputeServiceDto;
import uk.gov.hmcts.payment.api.contract.FeeServiceDto;
import uk.gov.hmcts.payment.api.contract.PaymentServiceDto;

import java.math.BigDecimal;
import java.util.List;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@JsonNaming(SnakeCaseStrategy.class)
@JsonInclude(NON_NULL)
@Builder(builderMethodName = "paymentFailureStatusDto")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class PaymentFailureStatusDto {

    @JsonProperty("service_request_reference")
    private String serviceRequestReference;

    @JsonProperty("ccd_case_number")
    private String ccdCaseNumber;

    @JsonProperty("service_request_amount")
    private BigDecimal serviceRequestAmount;

    @JsonProperty("service_request_status")
    private String serviceRequestStatus;

    private String orgId;
    private String responsibleParty;
    private String ccdAction;

    private List<FeeServiceDto> fees;
    private List<PaymentServiceDto>payment;

    private DisputeServiceDto dispute;

}
