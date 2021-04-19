package uk.gov.hmcts.payment.api.domain.service;

import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.hmcts.payment.api.exception.CaseDetailsNotFoundException;
import uk.gov.hmcts.payment.api.model.CaseDetails;
import uk.gov.hmcts.payment.api.model.CaseDetailsRepository;

import java.util.Optional;

public class CaseDetailsServiceImpl implements CaseDetailsService{

    @Autowired
    private CaseDetailsRepository caseDetailsRepository;

    @Override
    public CaseDetails findByCcdCaseNumber(String ccdCaseNumber) {
        Optional<CaseDetails> caseDetails = caseDetailsRepository.findByCcdCaseNumber(ccdCaseNumber);
        return caseDetails.orElseThrow(()->{return new CaseDetailsNotFoundException("Case Details Not found ");});
    }
}
