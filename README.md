## HMCTS Payment Gateway

HMCTS Payment Gateway is a small wrapper around GOV.UK Pay service adding some basic user/service authorization, 
enforcing useful payment reference structure and collecting data across multiple GOV.UK Pay accounts that will allow 
better financial reporting and reconciliation.   

### Integration prerequisites

For a successful integration with HMCTS Payment Gateway you will require the following:
* **GOV.UK Pay API key**. Before you start, you should get a dedicated GOV.UK Pay account created for you. Once it is done, 
you should use GOV.UK Pay admin console to create your API key(-s) and provide them to us.
* **IDAM**. All requests to Payment Gateway require a valid user JWT token to be passed in "Authorization" header. 
Please make sure your application is integrated with IDAM before you start.  
* **service-auth-externalProvider**. All requests to Payment Gateway require a valid service JWT token to be passed in 
"ServiceAuthorization" header. Please make sure your application is registered in service-auth-externalProvider-app and you are 
able to acquire service JWT tokens.

### Integration GOTCHAs

* **Stale Payment Status**. Neither HMCTS Payment Gateway, nor GOV.UK Pay support "PUSH" notifications for payment status update. 
Therefore, a situation where a user has made a payment but his redirection back to the "return" url failed (e.g. due to interrupted 
internet connection), would lead to a payment status not being reflected in your application until you query its status again.
You should take this into consideration and if necessary implement some background job for refreshing payment status.
* **Access authorization**. Payment gateway implements a simple url based authorization rule. User with id 999, will only be granted 
access to urls /users/999/payments/\*, any request to /users/{OTHER_ID}/payments/\* will result in 403.
* **Refunds**. Although, both HMCTS Payment Gateway and GOV.UK Pay implement refund endpoints, they **WILL NOT WORK** due to limitations
of MoJ financial arrangements  & back-office systems.

### Endpoints

* POST /users/{userId}/payments - create payment
* GET /users/{userId}/payments/{paymentId} - get payment
* POST /users/{userId}/payments/{paymentId}/cancel - cancel payment

Please refer to Swagger UI and Gov.UK Pay for more details.

### Useful Links
* https://gds-payments.gelato.io/docs/versions/1.0.0/resources/general
* https://git.reform.hmcts.net/common-components/reference-app
* https://git.reform.hmcts.net/common-components/reference-web
