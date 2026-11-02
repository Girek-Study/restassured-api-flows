package study.girek.flujos;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El reintento que nadie prueba.
 *
 * <p>Un cliente que no recibe la respuesta de un POST no sabe si la operación
 * ocurrió. Lo que hace es reintentar, y eso pasa de verdad: una red que corta,
 * un balanceador que cierra la conexión, un móvil que cambia de antena. Si la
 * API no distingue el reintento del segundo intento, el usuario acaba con dos
 * reservas y el aforo baja el doble.
 *
 * <p>No es un caso raro. Es lo que ocurre un día de venta de entradas.
 */
@DisplayName("F-03 · Reintentos y claves de idempotencia")
class IdempotenciaTest extends BaseDeFlujo {

  @Test
  @DisplayName("ID-01 · Dos POST con la misma clave crean una sola reserva")
  void laMismaClaveNoCreaDosReservas() {
    String token = tokenDe(VENDEDOR);
    String clave = UUID.randomUUID().toString();
    int antes = disponibleEn(EVENTO_GRANDE);

    Map<String, Object> cuerpo =
        Map.of("evento", EVENTO_GRANDE, "butacas", 2, "asistente", "AST-0020");

    String primera =
        como(token)
            .header("Idempotency-Key", clave)
            .body(cuerpo)
            .when()
            .post("/reservas")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // El reintento. El cliente manda exactamente lo mismo porque cree que lo
    // anterior no llegó.
    String segunda =
        como(token)
            .header("Idempotency-Key", clave)
            .body(cuerpo)
            .when()
            .post("/reservas")
            // 200 y no 201: no se ha creado nada nuevo. Un 201 aquí le diría
            // al cliente que hay una segunda reserva que no existe.
            .then()
            .statusCode(200)
            .header("idempotent-replay", "true")
            .extract()
            .path("id");

    assertEquals(primera, segunda, "El reintento tiene que devolver la MISMA reserva");

    // Y la comprobación que de verdad cierra el caso: el aforo bajó una vez.
    // Comparar los dos identificadores no basta —podrían coincidir por otro
    // motivo— y es el aforo lo que le duele al negocio.
    assertEquals(
        antes - 2, disponibleEn(EVENTO_GRANDE), "Un reintento no puede consumir aforo dos veces");

    como(token).when().delete("/reservas/{id}", primera).then().statusCode(204);
  }

  @Test
  @DisplayName("ID-02 · Sin clave, dos POST iguales crean dos reservas")
  void sinClaveSonDosReservasDistintas() {
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_GRANDE);

    String primera = reservar(token, EVENTO_GRANDE, 1, "AST-0021");
    String segunda = reservar(token, EVENTO_GRANDE, 1, "AST-0021");

    // Esto NO es un defecto: sin clave, la API no tiene forma de saber que la
    // segunda petición es un reintento y no una segunda compra del mismo
    // asistente. El caso está aquí para fijar esa frontera, porque es justo
    // donde alguien propondría «deduplicar por contenido» y romper la compra
    // legítima de dos entradas seguidas.
    assertNotEquals(primera, segunda);
    assertEquals(antes - 2, disponibleEn(EVENTO_GRANDE));

    como(token).when().delete("/reservas/{id}", primera).then().statusCode(204);
    como(token).when().delete("/reservas/{id}", segunda).then().statusCode(204);
  }

  @Test
  @DisplayName("ID-03 · El reintento devuelve la reserva, no un error")
  void elReintentoDevuelveElRecursoCompleto() {
    String token = tokenDe(VENDEDOR);
    String clave = UUID.randomUUID().toString();
    Map<String, Object> cuerpo =
        Map.of("evento", EVENTO_GRANDE, "butacas", 1, "asistente", "AST-0022");

    String id =
        como(token)
            .header("Idempotency-Key", clave)
            .body(cuerpo)
            .when()
            .post("/reservas")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // Un 409 «clave repetida» sería lo cómodo de implementar y lo inútil de
    // consumir: el cliente que reintenta sigue sin saber el id de su reserva,
    // y lo único que puede hacer es volver a intentarlo.
    como(token)
        .header("Idempotency-Key", clave)
        .body(cuerpo)
        .when()
        .post("/reservas")
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("estado", not(equalTo("ANULADA")))
        .body("evento", equalTo(EVENTO_GRANDE));

    como(token).when().delete("/reservas/{id}", id).then().statusCode(204);
  }
}
