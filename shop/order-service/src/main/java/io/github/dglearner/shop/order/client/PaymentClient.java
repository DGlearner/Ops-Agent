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

    /** How an order's charge ended, as reported by voiding it (which only takes effect if it was never charged). */
    public sealed interface VoidResult {
        record Charged(String paymentId) implements VoidResult {
        }

        record Declined(String reason) implements VoidResult {
        }

        /** No charge was made, and none can be made any more. */
        record Voided() implements VoidResult {
        }

        /** The charge is still at the provider; ask again later. */
        record Processing() implements VoidResult {
        }

        record Failed(CallFailure failure) implements VoidResult {
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
                        // A 400 is a validation failure, and PAYMENT_VOIDED means reconciliation already closed the
                        // payment: either way nothing was charged.
                        boolean notCharged = status == 400 || code.equals("PAYMENT_VOIDED");
                        return notCharged ? new ChargeResult.NotCharged(failure) : new ChargeResult.Unknown(failure);
                    });
        } catch (ResourceAccessException e) {
            CallFailure failure = CallFailure.from(e);
            return failure.kind() == CallFailure.Kind.NOT_SENT
                    ? new ChargeResult.NotCharged(failure) : new ChargeResult.Unknown(failure);
        }
    }

    public VoidResult voidUnlessCharged(String orderId) {
        try {
            return rest.post().uri("/api/v1/payments/{orderId}/void", orderId).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status != 200) {
                    String code = Problems.read(json, response).path("code").asString();
                    return new VoidResult.Failed(CallFailure.response(status, code));
                }
                PaymentBody payment = json.readValue(response.getBody(), PaymentBody.class);
                return switch (payment.status()) {
                    case "SUCCEEDED" -> new VoidResult.Charged(payment.paymentId());
                    case "DECLINED" -> new VoidResult.Declined(payment.declineReason());
                    case "VOIDED" -> new VoidResult.Voided();
                    default -> new VoidResult.Processing();
                };
            });
        } catch (ResourceAccessException e) {
            return new VoidResult.Failed(CallFailure.from(e));
        }
    }
}
