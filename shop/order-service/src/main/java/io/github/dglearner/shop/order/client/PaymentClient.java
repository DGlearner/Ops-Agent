package io.github.dglearner.shop.order.client;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Calls payment-service; every outcome is a value, never an exception. */
public class PaymentClient {

    public sealed interface ChargeResult {
        record Succeeded(String paymentId) implements ChargeResult {
        }

        record Declined(String reason) implements ChargeResult {
        }

        /** The request certainly did not charge: it never arrived, or was rejected before any money moved. */
        record NotCharged(CallFailure failure) implements ChargeResult {
        }

        /** Money may or may not have been taken; only the payment record can tell (reconciliation). */
        record Unknown(CallFailure failure) implements ChargeResult {
        }
    }

    private record ChargeBody(String orderId, String userId, long amountCents, String currency, String paymentToken) {
    }

    private record PaymentBody(String paymentId, String status, String declineReason) {
    }

    private final RestClient rest;
    private final JsonMapper json;

    public PaymentClient(RestClient rest, JsonMapper json) {
        this.rest = rest;
        this.json = json;
    }

    public ChargeResult charge(String orderId, String userId, long amountCents, String currency, String paymentToken) {
        try {
            return rest.post().uri("/api/v1/payments")
                    .body(new ChargeBody(orderId, userId, amountCents, currency, paymentToken))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 201 || status == 200) {
                            PaymentBody payment = json.readValue(response.getBody(), PaymentBody.class);
                            return switch (payment.status()) {
                                case "SUCCEEDED" -> new ChargeResult.Succeeded(payment.paymentId());
                                case "DECLINED" -> new ChargeResult.Declined(payment.declineReason());
                                default -> new ChargeResult.Unknown(
                                        new CallFailure(CallFailure.Kind.ERROR, "payment status " + payment.status()));
                            };
                        }
                        String code = Problems.read(json, response).path("code").asString();
                        CallFailure failure = CallFailure.response(status, code);
                        // A 400 is a validation failure: the payment service rejected the request before charging.
                        return status == 400 ? new ChargeResult.NotCharged(failure) : new ChargeResult.Unknown(failure);
                    });
        } catch (ResourceAccessException e) {
            CallFailure failure = CallFailure.from(e);
            return failure.kind() == CallFailure.Kind.NOT_SENT
                    ? new ChargeResult.NotCharged(failure) : new ChargeResult.Unknown(failure);
        }
    }
}
