package study.girek.flujos;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Qué deja la API cuando el flujo NO termina.
 *
 * <p>Este archivo es el repositorio entero. Un flujo de tres pasos tiene cuatro
 * formas de no llegar al final —anularlo, dejarlo caducar, que falle el pago o
 * que falle la emisión— y las cuatro responden con códigos distintos que una
 * prueba de API normal daría por correctos.
 *
 * <p>Lo que se comprueba aquí no es el código de respuesta del paso que falló.
 * Es el <b>aforo de después</b>, comparado con el de antes de empezar. Ningún
 * endpoint lo cuenta: hay que medirlo a los dos lados del flujo.
 */
@DisplayName("F-02 · Qué deja la API cuando el flujo se rompe a la mitad")
class InterrupcionDelFlujoTest extends BaseDeFlujo {

  @Test
  @DisplayName("IN-01 · Anular un bloqueo devuelve el aforo")
  void anularDevuelveElAforo() {
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_GRANDE);

    String reserva = reservar(token, EVENTO_GRANDE, 3, "AST-0010");
    assertEquals(antes - 3, disponibleEn(EVENTO_GRANDE), "El bloqueo tiene que reservar sitio");

    como(token).when().delete("/reservas/{id}", reserva).then().statusCode(204);

    assertEquals(antes, disponibleEn(EVENTO_GRANDE), "Anular tiene que devolver el aforo entero");
  }

  @Test
  @DisplayName("IN-02 · Un bloqueo que caduca devuelve el aforo solo")
  void elBloqueoCaducadoDevuelveElAforo() {
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_GRANDE);

    reservar(token, EVENTO_GRANDE, 4, "AST-0011");
    assertEquals(antes - 4, disponibleEn(EVENTO_GRANDE));

    // Sin Thread.sleep. Un sleep fijo es lento cuando acierta y mentiroso
    // cuando no: si la máquina va justa, termina antes que el evento y la
    // prueba falla por el reloj, no por la API. Awaitility sondea hasta que
    // la condición se cumpla o se agote el plazo.
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () ->
                assertEquals(
                    antes,
                    disponibleEn(EVENTO_GRANDE),
                    "Un bloqueo caducado tiene que liberar sus butacas"));
  }

  @Test
  @DisplayName("IN-03 · Si el pago falla, el aforo tiene que volver")
  void siElPagoFallaElAforoVuelve() {
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_GRANDE);

    String reserva = reservar(token, EVENTO_GRANDE, 2, "AST-0012");

    // Se le pide a la API que el cobro reviente. Es la parte del flujo que en
    // producción ocurre sola, de madrugada, cuando la pasarela de pago tiene
    // un mal rato.
    como(token)
        .header("x-fallo-en", "pago")
        .when()
        .post("/reservas/{id}/pago", reserva)
        .then()
        .statusCode(500)
        .body("error.codigo", equalTo("ERROR_INTERNO"));

    // Hasta aquí todo es «correcto»: la API avisó del fallo con un 500 y el
    // cliente dará la operación por no hecha. La pregunta que nadie hace es
    // qué pasó con las butacas.
    //
    // Se espera porque la respuesta correcta admite dos formas: revertir a
    // BLOQUEADA y dejar que caduque, o liberar en el acto. Las dos acaban en
    // el mismo aforo; exigir una de las dos sería imponer una implementación.
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () ->
                assertEquals(
                    antes,
                    disponibleEn(EVENTO_GRANDE),
                    """
                    Un pago que falla no puede quedarse con las butacas.
                    Son asientos que nadie compró y que nadie podrá comprar:
                    el 500 le dijo al cliente que la operación no se hizo."""));
  }

  @Test
  @DisplayName("IN-04 · Si falla la emisión, el aforo se queda consumido, y está bien")
  void siFallaLaEmisionElAforoSigueConsumido() {
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_GRANDE);

    String reserva = reservar(token, EVENTO_GRANDE, 1, "AST-0013");
    como(token).when().post("/reservas/{id}/pago", reserva).then().statusCode(200);

    como(token)
        .header("x-fallo-en", "emision")
        .when()
        .post("/reservas/{id}/emision", reserva)
        .then()
        .statusCode(500);

    // Este 500 es idéntico al de IN-03: mismo código, mismo cuerpo. Y es su
    // opuesto. Aquí la butaca está pagada, así que consumirla es lo correcto,
    // y el cliente puede reintentar la emisión sin perder nada.
    //
    // Una suite que juzgue los dos casos por la respuesta los da por iguales.
    // La diferencia solo existe en el estado de después.
    assertEquals(
        antes - 1,
        disponibleEn(EVENTO_GRANDE),
        "Una butaca pagada sigue vendida aunque falle la emisión");

    como(token)
        .when()
        .get("/reservas/{id}", reserva)
        .then()
        .statusCode(200)
        .body("estado", equalTo("PAGADA"));

    // Y el reintento funciona: es un fallo recuperable.
    como(token)
        .when()
        .post("/reservas/{id}/emision", reserva)
        .then()
        .statusCode(200)
        .body("estado", equalTo("EMITIDA"));
  }
}
