package com.apexstore.client;

import com.apexstore.client.generated.Checkout.CheckoutRequest;
import com.apexstore.client.generated.Checkout.CheckoutResponse;
import com.apexstore.client.generated.Checkout.CheckoutServicePrx;
import com.apexstore.client.generated.Checkout.CheckoutUnavailable;
import com.apexstore.client.generated.Checkout.InvalidCheckoutRequest;
import com.apexstore.client.generated.Checkout.PaymentMethod;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectNotExistException;
import com.zeroc.Ice.Util;

import java.util.Scanner;

/** Cliente CLI de ApexStore: envía solicitudes de compra a CheckoutService mediante Ice. */
public final class CheckoutClientApp {

    private static final String DEFAULT_HOST = "10.232.215.231";
    private static final int DEFAULT_PORT = 10002;
    private static final String IDENTITY = "CheckoutService";

    public static void main(String[] args) {
        // exit después de run(): así el Communicator ya se cerró limpiamente
        System.exit(run(args));
    }

    private static int run(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--help" -> {
                        System.out.println("Uso: CheckoutClientApp [--host HOST] [--port PUERTO]");
                        return 0;
                    }
                    default -> {
                        System.err.println("Argumento desconocido: " + args[i]);
                        return 2;
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.err.println("Argumentos invalidos. Use --help.");
            return 2;
        }

        // Dirección del servicio:  identidad : transporte -h host -p puerto
        String proxyString = "%s:default -h %s -p %d".formatted(IDENTITY, host, port);

        // Propiedad de Ice: ninguna llamada espera más de 10 s (evita que el CLI se cuelgue)
        String[] iceArgs = {"--Ice.Default.InvocationTimeout=10000"};

        try (Communicator communicator = Util.initialize(iceArgs);
             Scanner in = new Scanner(System.in)) {

            System.out.println("Conectando a " + proxyString + " ...");
            var base = communicator.stringToProxy(proxyString);
            CheckoutServicePrx checkout = CheckoutServicePrx.checkedCast(base); // primera llamada de red
            if (checkout == null) {
                System.err.println("El objeto remoto no es un CheckoutService.");
                return 1;
            }
            System.out.println("Conectado.");
            menuLoop(checkout, in);
            return 0;

        } catch (ObjectNotExistException e) {
            System.err.println("El servidor responde, pero no tiene un objeto '" + IDENTITY + "'.");
            return 1;
        } catch (LocalException e) {
            System.err.println("No se pudo conectar (" + e.getClass().getSimpleName() + "). "
                    + "¿Esta corriendo ProcessorMain en " + host + ":" + port + "?");
            return 1;
        }
    }

    private static void menuLoop(CheckoutServicePrx checkout, Scanner in) {
        while (true) {
            System.out.println("\n=== ApexStore - Checkout ===");
            System.out.println("1) Nueva compra");
            System.out.println("2) Salir");
            String option = prompt(in, "Opcion");
            if (option == null || option.equals("2")) {
                System.out.println("Hasta luego.");
                return;
            }
            if (option.equals("1")) {
                newPurchase(checkout, in);
            } else {
                System.out.println("Opcion no valida.");
            }
        }
    }

    private static void newPurchase(CheckoutServicePrx checkout, Scanner in) {
        String orderId = prompt(in, "Id de la orden (vacio = generar)");
        if (orderId == null) {
            return;
        }
        if (orderId.isBlank()) {
            orderId = "ORD-" + System.currentTimeMillis();
        }

        PaymentMethod method = askMethod(in);
        if (method == null) {
            return;
        }
        String amount = prompt(in, "Monto (ej. 250.50)");
        String currency = prompt(in, "Moneda (ej. COP, USD) [COP]");
        if (amount == null || currency == null) {
            return;
        }
        currency = currency.isBlank() ? "COP" : currency.strip().toUpperCase();

        var request = new CheckoutRequest(orderId, method, amount.strip(), currency);
        System.out.println("\n-> Enviando orden " + orderId + " (" + method + ", " + amount + " " + currency + ")");

        try {
            CheckoutResponse r = checkout.checkout(request);    // llamada remota
            System.out.println("<- Respuesta del servidor:");
            System.out.println("   Orden:      " + r.orderId);
            System.out.println("   Estado:     " + r.status);
            if (!r.gatewayReference.isEmpty()) {
                System.out.println("   Referencia: " + r.gatewayReference);
            }
            if (!r.rejectionReason.isEmpty()) {
                System.out.println("   Motivo:     " + r.rejectionReason);
            }
        } catch (InvalidCheckoutRequest e) {
            System.out.println("<- Solicitud invalida. Campo '" + e.field + "': " + e.reason);
        } catch (CheckoutUnavailable e) {
            System.out.println("<- Servicio no disponible: " + e.reason + ". Intente de nuevo mas tarde.");
        } catch (LocalException e) {
            System.out.println("<- Error de comunicacion (" + e.getClass().getSimpleName() + "). "
                    + "La compra pudo o no haberse registrado; verifique antes de reintentar.");
        }
    }

    private static PaymentMethod askMethod(Scanner in) {
        System.out.println("Metodo de pago: 1) STRIPE  2) PSE  3) CRYPTO");
        while (true) {
            String choice = prompt(in, "Metodo");
            if (choice == null) {
                return null;
            }
            switch (choice.strip()) {
                case "1": return PaymentMethod.STRIPE;
                case "2": return PaymentMethod.PSE;
                case "3": return PaymentMethod.CRYPTO;
                default: System.out.println("Elija 1, 2 o 3.");
            }
        }
    }

    /** Devuelve la línea escrita, o null si se cerró la entrada (Ctrl+D / Ctrl+Z). */
    private static String prompt(Scanner in, String label) {
        System.out.print(label + ": ");
        return in.hasNextLine() ? in.nextLine() : null;
    }
}
