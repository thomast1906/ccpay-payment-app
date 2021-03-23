package uk.gov.hmcts.payment.api.dto.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.*;

import javax.validation.constraints.NotNull;
import java.util.List;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
@JsonInclude(NON_NULL)
@Builder(builderMethodName = "orderDtoWith")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class OrderDto {

    //--16 digit check
    @NotNull
    private String ccdCaseNumber;

    @NotNull
    private List<OrderFeeDto> fees;

}
