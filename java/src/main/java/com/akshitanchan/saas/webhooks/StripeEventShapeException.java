package com.akshitanchan.saas.webhooks;

// thrown when a handled stripe event type arrives with a data.object that isn't a json
// object; the controller catches this, marks the ledger row failed, and returns 500 so the
// same event id can be retried once stripe (or a test) resends it with a good payload
class StripeEventShapeException extends RuntimeException {

    StripeEventShapeException(String message) {
        super(message);
    }
}
