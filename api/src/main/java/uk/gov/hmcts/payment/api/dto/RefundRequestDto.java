package uk.gov.hmcts.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(NON_NULL)
@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder(builderMethodName = "refundRequestDtoWith")
public class RefundRequestDto {

    private String paymentReference;

    private String refundReason;

    private BigDecimal refundAmount;

    private String ccdCaseNumber;

    private String feeIds;
}
