#pragma once

// COPIA del contrato del servidor. Deben coincidir EXACTAMENTE:
// nombre del módulo, de la interfaz, de las operaciones, y el orden y tipo de los campos.
// Lo único que puede cambiar es el paquete Java generado (es solo local, no viaja por la red).
[["java:package:com.apexstore.client.generated"]]

module Checkout
{
    enum PaymentMethod { STRIPE, PSE, CRYPTO }

    enum CheckoutStatus { ACCEPTED, REJECTED }

    struct CheckoutRequest
    {
        string orderId;
        PaymentMethod method;
        string amount;
        string currency;
    }

    struct CheckoutResponse
    {
        string orderId;
        CheckoutStatus status;
        string gatewayReference;
        string rejectionReason;
    }

    exception InvalidCheckoutRequest
    {
        string field;
        string reason;
    }

    exception CheckoutUnavailable
    {
        string reason;
    }

    // En el diagrama este puerto se llama "manageHttpSales";
    // en Ice es la interfaz CheckoutService.
    interface CheckoutService
    {
        CheckoutResponse checkout(CheckoutRequest request)
            throws InvalidCheckoutRequest, CheckoutUnavailable;
    }
}
