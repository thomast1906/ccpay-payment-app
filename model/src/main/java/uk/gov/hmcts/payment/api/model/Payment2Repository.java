package uk.gov.hmcts.payment.api.model;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface Payment2Repository extends CrudRepository<Payment, Integer>{

    Optional<Payment> findByReferenceAndPaymentMethod(String reference, PaymentMethod paymentMethod);

    Optional<Payment> findByReference(String reference);

    Optional<Payment> findByCcdCaseNumber(String ccdCaseNumber);

}
