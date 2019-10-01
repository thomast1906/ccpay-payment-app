package uk.gov.hmcts.payment.api.controllers;

import io.swagger.annotations.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.payment.api.dto.PaymentSearchCriteria;
import uk.gov.hmcts.payment.api.model.PaymentFeeLink;
import uk.gov.hmcts.payment.api.service.PaymentService;
import uk.gov.hmcts.payment.api.util.ExcelGeneratorUtil;
import uk.gov.hmcts.payment.api.util.ReportType;
import uk.gov.hmcts.payment.api.v1.model.exceptions.PaymentException;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static uk.gov.hmcts.payment.api.util.DateUtil.getDateForReportName;
import static uk.gov.hmcts.payment.api.util.DateUtil.getDateTimeForReportName;

@RestController
@Api(tags = {"Bulk Scanning Report"})
@SwaggerDefinition(tags = {@Tag(name = "BulkScanningReportController", description = "Bulk Scanning report REST API")})
public class BulkScanningReportController {
    private final PaymentService<PaymentFeeLink, String> paymentService;
    private static final Logger LOG = LoggerFactory.getLogger(BulkScanningReportController.class);

    @Autowired
    public BulkScanningReportController(PaymentService<PaymentFeeLink, String> paymentService) {
        this.paymentService = paymentService;
    }

    @ApiOperation("API to generate Report for Bulk Scan Payment System")
    @ApiResponses({
        @ApiResponse(code = 200, message = "Report Generated"),
        @ApiResponse(code = 404, message = "No Data found to generate Report")
    })
    @GetMapping("/payment/bulkscan-report-download")
    public ResponseEntity<?> retrieveBulkScanReports(
        @RequestParam("date_from") Date fromDate,
        @RequestParam("date_to") Date toDate,
        @RequestParam("report_type") ReportType reportType, HttpServletResponse response) {
        byte[] reportBytes = null;
        HSSFWorkbook workbook = new HSSFWorkbook();
        try {

            List<PaymentFeeLink> paymentFeeLinks = paymentService
                .search(
                    PaymentSearchCriteria
                        .searchCriteriaWith()
                        .startDate(fromDate)
                        .endDate(toDate)
                        .build()
                );


            if (Optional.ofNullable(paymentFeeLinks).isPresent()) {
                LOG.info("No of Records exists : {}", paymentFeeLinks.size());

                workbook = (HSSFWorkbook) ExcelGeneratorUtil.exportToExcel(reportType, paymentFeeLinks);
            }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                workbook.write(baos);
                reportBytes = baos.toByteArray();

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("application/vnd.ms-excel"));

                String fileName = reportType.toString() + "_"
                    + getDateForReportName(fromDate) + "_To_"
                    + getDateForReportName(toDate) + "_RUN_"
                    + getDateTimeForReportName(new Date(System.currentTimeMillis()))
                    + ".xls";
                response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
                return new ResponseEntity<byte[]>(reportBytes, headers, HttpStatus.OK);

        } catch (Exception ex) {
            throw new PaymentException(ex);
        } finally {
            try {
                workbook.close();
            } catch (IOException e) {
                LOG.error(e.getMessage());
            }
        }
    }
}
