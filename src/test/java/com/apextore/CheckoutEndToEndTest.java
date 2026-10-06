package com.apexstore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.apexstore.client.generated.Checkout.CheckoutRequest;
import com.apexstore.client.generated.Checkout.CheckoutResponse;
import com.apexstore.client.generated.Checkout.CheckoutServicePrx;
import com.apexstore.client.generated.Checkout.CheckoutStatus;
import com.apexstore.client.generated.Checkout.InvalidCheckoutRequest;
import com.apexstore.client.generated.Checkout.PaymentMethod;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.Util;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Prueba end-to-end de los 3 nodos:
 *
 *   [Cliente] --Ice:10002--> [Server: Checkout + Processor] --Ice:10000--> [Pasarela]
 *                                   ^                                          |
 *                                   +--------- callback Ice:10001 -------------+
 *
 * Requiere los nodos corriendo (ver scripts/e2e-preflight.ps1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutEndToEndTest {

    private static final String ICE_TIMEOUT = "--Ice.Default.InvocationTimeout=15000";

    private final E2EConfig config = E2EConfig.fromEnv();
    private Communicator communicator;
    private CheckoutServicePrx checkout;

    @BeforeAll
    void connectToServer() {
        communicator = Util.initialize(new String[] {ICE_TIMEOUT});
        try {
            checkout = CheckoutServicePrx.checkedCast(communicator.stringToProxy(config.proxyString()));
        } catch (LocalException e) {
            communicator.close();
            fail("Nodo Server inaccesible en " + config.proxyString() + " (" + e.getClass().getSimpleName()
                    + "). ¿Está corriendo ProcessorMain (y la pasarela, de la que depende su arranque)?", e);
        }
        assertNotNull(checkout, "El objeto remoto no es un CheckoutService");
    }

    @AfterAll
    void shutdown() {
        if (communicator != null) {
            communicator.close();
        }
    }

    // ---- Salto 1: Cliente -> Server ---------------------------------------------------

    @Test
    @DisplayName("1. El nodo Server responde a ice_ping")
    void serverNodeIsReachable() {
        assertDoesNotThrow(() -> checkout.ice_ping());
    }

    @Test
    @DisplayName("2. El Server valida y rechaza una orden inválida sin tocar BD ni pasarela")
    void invalidOrderIsRejectedByServer() throws Exception {
        String orderId = newOrderId();

        var e = assertThrows(InvalidCheckoutRequest.class,
                () -> checkout.checkout(new CheckoutRequest(orderId, PaymentMethod.STRIPE, "-5", "USD")));

        assertEquals("amount", e.field);
        if (config.dbEnabled()) {
            assertTrue(new TransactionDb(config).findStatus(orderId).isEmpty(),
                    "Una orden inválida no debería haberse persistido");
        }
    }

    // ---- Salto 2: Server -> Pasarela --------------------------------------------------

    @ParameterizedTest(name = "3. Cliente -> Server -> Pasarela con {0}")
    @EnumSource(PaymentMethod.class)
    void validOrderReachesGateway(PaymentMethod method) {
        String orderId = newOrderId();

        // Si la pasarela fuera inalcanzable, el Server lanzaría CheckoutUnavailable.
        // El Server nunca inventa un estado: ACCEPTED/REJECTED sale del acuse de la pasarela.
        CheckoutResponse response = assertDoesNotThrow(
                () -> checkout.checkout(new CheckoutRequest(orderId, method, "250.50", currencyFor(method))),
                "El Server no pudo completar la llamada a la pasarela");

        assertEquals(orderId, response.orderId);
        assertNotNull(response.status);
        System.out.printf("[E2E] %s %s -> %s ref='%s' motivo='%s'%n",
                method, orderId, response.status, response.gatewayReference, response.rejectionReason);
    }

    // ---- Salto 3: Pasarela -> Server (callback) -> BD ---------------------------------

    @ParameterizedTest(name = "4. La transacción {0} se persiste y alcanza estado final")
    @EnumSource(PaymentMethod.class)
    void transactionIsPersistedAndReachesFinalState(PaymentMethod method) throws Exception {
        assumeTrue(config.dbEnabled(), "Defina E2E_DB_URL para verificar la base de datos");
        var db = new TransactionDb(config);
        String orderId = newOrderId();

        CheckoutResponse response =
                checkout.checkout(new CheckoutRequest(orderId, method, "250.50", currencyFor(method)));

        assertTrue(db.findStatus(orderId).isPresent(),
                "No existe fila para la orden: ¿se llamó a persistInitialTransaction?");

        String finalStatus = db.awaitFinalStatus(orderId, config.callbackTimeout());
        if (response.status == CheckoutStatus.REJECTED) {
            assertEquals("FAILED", finalStatus, "Un rechazo síncrono debe quedar como FAILED");
        } else {
            assertTrue(Set.of("CONFIRMED", "FAILED").contains(finalStatus),
                    "La transacción sigue en '" + finalStatus + "' tras " + config.callbackTimeout()
                            + ". ¿La pasarela envió el callback al puerto 10001?");
        }
    }

    // ---- Caso negativo --------------------------------------------------------------------

    @Test
    @DisplayName("5. El cliente falla rápido y de forma controlada si el Server no existe")
    void clientFailsFastWhenServerIsDown() {
        try (Communicator other = Util.initialize(new String[] {"--Ice.Default.InvocationTimeout=3000"})) {
            var proxy = other.stringToProxy("CheckoutService:default -h localhost -p 1");
            assertThrows(LocalException.class, () -> CheckoutServicePrx.checkedCast(proxy));
        }
    }

    // ---- Utilidades -----------------------------------------------------------------------

    private static String newOrderId() {
        return "E2E-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String currencyFor(PaymentMethod method) {
        return method == PaymentMethod.PSE ? "COP" : "USD";
    }
}