package study.girek.flujos;

import static io.restassured.RestAssured.given;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;

/**
 * Lo que comparten todos los flujos: a dónde se apunta, cómo se abre sesión y
 * cómo se lee el aforo.
 *
 * <p>Nada de esto son pruebas. Está aquí para que un escenario de flujo se lea
 * como el flujo que describe y no como una pila de cabeceras.
 */
abstract class BaseDeFlujo {

  /** El vendedor puede reservar, pagar, emitir y anular. */
  protected static final Map<String, String> VENDEDOR =
      Map.of("usuario", "taquilla", "clave", "taquilla-2026");

  /** El auditor solo lee. Hace falta para distinguir 401 de 403. */
  protected static final Map<String, String> AUDITOR =
      Map.of("usuario", "auditoria", "clave", "auditoria-2026");

  protected static final String EVENTO_GRANDE = "EVT-001";
  protected static final String EVENTO_PEQUENO = "EVT-002";

  private static RequestSpecification base;

  @BeforeAll
  static void prepararRestAssured() {
    String url = System.getProperty("api.url", "http://localhost:8095");

    base =
        new RequestSpecBuilder()
            .setBaseUri(url)
            .setContentType(ContentType.JSON)
            // Registrar el cuerpo solo cuando la prueba falla. Registrarlo
            // siempre deja un log que nadie lee; no registrarlo nunca deja un
            // fallo que hay que reproducir a mano para entenderlo.
            .log(LogDetail.URI)
            .build();

    RestAssured.requestSpecification = base;
    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
  }

  /** Abre sesión y devuelve el token. Un escenario de flujo no debería tener que mirar esto. */
  protected static String tokenDe(Map<String, String> credenciales) {
    return given()
        .body(credenciales)
        .when()
        .post("/sesiones")
        .then()
        .statusCode(201)
        .extract()
        .path("token");
  }

  /** Una petición ya autenticada, para no repetir la cabecera en cada paso. */
  protected static RequestSpecification como(String token) {
    return given().header("Authorization", "Bearer " + token);
  }

  /**
   * Butacas que de verdad se pueden vender ahora mismo.
   *
   * <p>Es el número que mide este repositorio. Un flujo interrumpido se juzga
   * por lo que le hace a esta cifra, no por el código que devolvió el paso que
   * falló.
   */
  protected static int disponibleEn(String evento) {
    return given()
        .when()
        .get("/eventos/{evento}/aforo", evento)
        .then()
        .statusCode(200)
        .extract()
        .path("disponible");
  }

  /**
   * El aforo de un evento, con sus tres números.
   *
   * <p>Existe para que ninguna comprobación tenga que escribir una cifra
   * absoluta. {@code vendido == 2} parece inofensivo y es falso en cuanto otro
   * escenario haya vendido algo antes: el aforo es estado compartido y el
   * orden entre clases de prueba no está garantizado. Con el aforo de antes
   * en la mano, lo que se afirma es el movimiento, y eso no depende de quién
   * haya corrido primero.
   */
  protected record Aforo(int total, int bloqueado, int vendido, int disponible) {}

  protected static Aforo aforoDe(String evento) {
    var cuerpo =
        given()
            .when()
            .get("/eventos/{evento}/aforo", evento)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    return new Aforo(
        cuerpo.getInt("total"),
        cuerpo.getInt("bloqueado"),
        cuerpo.getInt("vendido"),
        cuerpo.getInt("disponible"));
  }

  /** Crea una reserva bloqueada y devuelve su id. El paso 1 del flujo, sin ceremonia. */
  protected static String reservar(String token, String evento, int butacas, String asistente) {
    return como(token)
        .body(Map.of("evento", evento, "butacas", butacas, "asistente", asistente))
        .when()
        .post("/reservas")
        .then()
        .statusCode(201)
        .body("estado", org.hamcrest.Matchers.equalTo("BLOQUEADA"))
        .extract()
        .path("id");
  }
}
