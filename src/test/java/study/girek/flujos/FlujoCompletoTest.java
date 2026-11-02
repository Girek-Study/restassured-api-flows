package study.girek.flujos;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El camino feliz: reservar, pagar, emitir.
 *
 * <p>Tres pasos y una sola afirmación que de verdad importa, al final: el aforo
 * bajó exactamente en las butacas que se vendieron. Ni una más, ni una menos.
 */
@DisplayName("F-01 · El flujo completo de reserva")
class FlujoCompletoTest extends BaseDeFlujo {

  @Test
  @DisplayName("FL-01 · Reservar, pagar y emitir mueve el aforo una sola vez")
  void elFlujoCompletoConsumeLasButacasUnaVez() {
    String token = tokenDe(VENDEDOR);

    // El aforo entero de antes, no solo el disponible. Los tres números se
    // comparan con su propio punto de partida: una cifra absoluta aquí
    // —`vendido == 2`— es falsa en cuanto otro escenario haya vendido algo
    // antes, y el orden entre clases de prueba no está garantizado. Costó una
    // corrida del CI; está en docs/05-hallazgos.md.
    Aforo antes = aforoDe(EVENTO_GRANDE);

    String reserva = reservar(token, EVENTO_GRANDE, 2, "AST-0001");

    // Tras el paso 1 las butacas están bloqueadas, no vendidas. La diferencia
    // importa: un bloqueo caduca solo y una venta no.
    Aforo trasReservar = aforoDe(EVENTO_GRANDE);
    assertEquals(antes.bloqueado() + 2, trasReservar.bloqueado(), "la reserva bloquea");
    assertEquals(antes.vendido(), trasReservar.vendido(), "y no vende todavía");
    assertEquals(antes.disponible() - 2, trasReservar.disponible());

    como(token)
        .when()
        .post("/reservas/{id}/pago", reserva)
        .then()
        .statusCode(200)
        .body("estado", equalTo("PAGADA"))
        .body("pagadaEn", notNullValue());

    // Tras el pago las mismas butacas pasan de bloqueadas a vendidas. El
    // disponible NO se mueve: si bajara otra vez, se estarían contando dos
    // veces, que es el error de aforo más común que hay.
    Aforo trasPagar = aforoDe(EVENTO_GRANDE);
    assertEquals(antes.bloqueado(), trasPagar.bloqueado(), "el bloqueo se suelta");
    assertEquals(antes.vendido() + 2, trasPagar.vendido(), "y pasa a venta");
    assertEquals(
        antes.disponible() - 2,
        trasPagar.disponible(),
        "pagar no puede volver a descontar las mismas butacas");

    como(token)
        .when()
        .post("/reservas/{id}/emision", reserva)
        .then()
        .statusCode(200)
        .body("estado", equalTo("EMITIDA"))
        .body("billete", notNullValue());

    assertEquals(
        antes.disponible() - 2,
        disponibleEn(EVENTO_GRANDE),
        "El flujo completo tiene que consumir exactamente las butacas vendidas");
  }

  @Test
  @DisplayName("FL-02 · La reserva recién creada dice hasta cuándo vale")
  void laReservaTraeSuCaducidad() {
    String token = tokenDe(VENDEDOR);
    String reserva = reservar(token, EVENTO_GRANDE, 1, "AST-0002");

    // `expiraEn` no es un adorno: es lo único que permite a un cliente saber
    // cuánto tiempo tiene para pagar. Sin ese campo, la única forma de
    // enterarse de que el bloqueo caducó es que el pago falle.
    como(token)
        .when()
        .get("/reservas/{id}", reserva)
        .then()
        .statusCode(200)
        .body("estado", equalTo("BLOQUEADA"))
        .body("expiraEn", notNullValue())
        .body("billete", org.hamcrest.Matchers.nullValue());

    // Deja el aforo como estaba: esta prueba no vende nada.
    como(token).when().delete("/reservas/{id}", reserva).then().statusCode(204);
  }
}
