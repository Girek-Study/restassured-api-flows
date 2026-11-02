package study.girek.flujos;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los pasos pedidos en el orden equivocado.
 *
 * <p>Un flujo de tres pasos tiene seis órdenes posibles y solo uno es el bueno.
 * Los otros cinco los pide alguien: un reintento que llega tarde, dos pestañas
 * abiertas, un script de integración escrito mirando otra versión de la
 * documentación.
 *
 * <p>La aserción que importa en todos es la misma: <b>el estado equivocado no
 * mueve el aforo</b>. Un 409 que además consume butacas es peor que un 500.
 */
@DisplayName("F-04 · Pasos en el orden equivocado")
class ReglasDeEstadoTest extends BaseDeFlujo {

  @Test
  @DisplayName("ES-01 · No se emite un billete sin pagar")
  void noSeEmiteSinPagar() {
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_GRANDE);
    String reserva = reservar(token, EVENTO_GRANDE, 1, "AST-0030");

    como(token)
        .when()
        .post("/reservas/{id}/emision", reserva)
        .then()
        .statusCode(409)
        .body("error.codigo", equalTo("ESTADO_INVALIDO"));

    // Sigue bloqueada, no vendida: el intento fallido no adelantó el flujo.
    assertEquals(antes - 1, disponibleEn(EVENTO_GRANDE));
    como(token)
        .when()
        .get("/reservas/{id}", reserva)
        .then()
        .body("estado", equalTo("BLOQUEADA"));

    como(token).when().delete("/reservas/{id}", reserva).then().statusCode(204);
    assertEquals(antes, disponibleEn(EVENTO_GRANDE));
  }

  @Test
  @DisplayName("ES-02 · No se paga dos veces la misma reserva")
  void noSePagaDosVeces() {
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_GRANDE);
    String reserva = reservar(token, EVENTO_GRANDE, 2, "AST-0031");

    como(token).when().post("/reservas/{id}/pago", reserva).then().statusCode(200);

    como(token)
        .when()
        .post("/reservas/{id}/pago", reserva)
        .then()
        .statusCode(409)
        .body("error.codigo", equalTo("ESTADO_INVALIDO"));

    // El segundo pago no puede consumir otras dos butacas. Es el defecto de
    // doble cargo visto desde el inventario, y aparece en cuanto alguien pulsa
    // dos veces el botón.
    assertEquals(antes - 2, disponibleEn(EVENTO_GRANDE), "Pagar dos veces no consume el doble");
  }

  @Test
  @DisplayName("ES-03 · Un billete emitido no se anula por la vía del bloqueo")
  void unBilleteEmitidoNoSeAnulaAsi() {
    String token = tokenDe(VENDEDOR);
    String reserva = reservar(token, EVENTO_GRANDE, 1, "AST-0032");
    como(token).when().post("/reservas/{id}/pago", reserva).then().statusCode(200);
    como(token).when().post("/reservas/{id}/emision", reserva).then().statusCode(200);

    // Anular un billete emitido es una devolución, con su reembolso y su
    // política. Dejar que se haga por la misma puerta que libera un bloqueo
    // es cómo se devuelve dinero sin que conste en ningún sitio.
    como(token)
        .when()
        .delete("/reservas/{id}", reserva)
        .then()
        .statusCode(409)
        .body("error.codigo", equalTo("ESTADO_INVALIDO"));
  }

  @Test
  @DisplayName("ES-04 · El auditor no anula: 403, no 401")
  void elAuditorNoAnula() {
    String vendedor = tokenDe(VENDEDOR);
    String auditor = tokenDe(AUDITOR);
    String reserva = reservar(vendedor, EVENTO_GRANDE, 1, "AST-0033");

    // 403 y no 401. El auditor está autenticado; lo que le falta es permiso.
    // Un 401 aquí lo manda a renovar un token que está perfectamente bien, y
    // el bucle de renovación que sale de ahí es un clásico de los informes de
    // incidencia.
    como(auditor)
        .when()
        .delete("/reservas/{id}", reserva)
        .then()
        .statusCode(403)
        .body("error.codigo", equalTo("SIN_PERMISO"));

    como(vendedor).when().delete("/reservas/{id}", reserva).then().statusCode(204);
  }

  @Test
  @DisplayName("ES-05 · Sin token no se reserva nada")
  void sinTokenNoSeReserva() {
    int antes = disponibleEn(EVENTO_GRANDE);

    given()
        .body(Map.of("evento", EVENTO_GRANDE, "butacas", 1, "asistente", "AST-0034"))
        .when()
        .post("/reservas")
        .then()
        .statusCode(401)
        .body("error.codigo", equalTo("NO_AUTENTICADO"));

    // Y no basta con el 401: hay que comprobar que no creó nada. Un endpoint
    // que valida la sesión después de escribir responde 401 y deja la reserva
    // hecha, y eso no se ve en el código de estado.
    assertEquals(antes, disponibleEn(EVENTO_GRANDE), "Un 401 no puede haber consumido aforo");
  }
}
