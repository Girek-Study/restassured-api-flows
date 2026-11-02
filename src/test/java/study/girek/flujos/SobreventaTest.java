package study.girek.flujos;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Que no se vendan más butacas de las que hay.
 *
 * <p>Se usa el evento pequeño —cuatro butacas— porque una regla de aforo solo
 * se puede comprobar agotándolo, y agotar cincuenta butacas a base de
 * peticiones es ruido.
 */
@DisplayName("F-05 · Aforo y sobreventa")
class SobreventaTest extends BaseDeFlujo {

  /** Butacas del evento pequeño. Cabe en una petición, que es justo para lo que sirve. */
  private static final int AFORO_PEQUENO = 4;

  /**
   * Espera a que el evento pequeño vuelva a estar entero.
   *
   * <p>Estas tres pruebas comparten el mismo aforo y dejan bloqueos detrás que
   * caducan solos. JUnit no garantiza el orden entre ellas, así que cada una
   * empieza esperando a tener el evento para sí: sin esto, la que corra
   * segunda ve un aforo mordido por la primera y falla por un motivo que no
   * tiene nada que ver con lo que comprueba.
   *
   * <p>Es la alternativa honesta a `@Order`. Ordenar las pruebas esconde el
   * acoplamiento; esperar lo resuelve.
   */
  private static void conElEventoEntero() {
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () ->
                assertEquals(
                    AFORO_PEQUENO,
                    disponibleEn(EVENTO_PEQUENO),
                    "El evento pequeño tiene que estar libre antes de empezar"));
  }

  @Test
  @DisplayName("SV-01 · Pedir más butacas de las que quedan devuelve 409 y dice cuántas hay")
  void noSeVendeMasDeLoQueHay() {
    conElEventoEntero();
    String token = tokenDe(VENDEDOR);
    int disponible = disponibleEn(EVENTO_PEQUENO);

    como(token)
        .body(
            Map.of("evento", EVENTO_PEQUENO, "butacas", disponible + 1, "asistente", "AST-0040"))
        .when()
        .post("/reservas")
        .then()
        .statusCode(409)
        .body("error.codigo", equalTo("AFORO_INSUFICIENTE"))
        // Que el error diga cuántas quedan no es un lujo: sin ese dato el
        // cliente solo puede reintentar a ciegas, bajando de uno en uno.
        .body("error.disponible", equalTo(disponible));

    assertEquals(disponible, disponibleEn(EVENTO_PEQUENO), "Un 409 no puede consumir aforo");
  }

  @Test
  @DisplayName("SV-02 · Un bloqueo ajeno reduce lo que yo puedo reservar")
  void elBloqueoAjenoCuenta() {
    conElEventoEntero();
    String token = tokenDe(VENDEDOR);
    int antes = disponibleEn(EVENTO_PEQUENO);

    String deOtro = reservar(token, EVENTO_PEQUENO, antes - 1, "AST-0041");

    // El disponible cuenta los bloqueos, no solo las ventas. Si no los
    // contara, dos clientes podrían bloquear la misma butaca y el choque
    // aparecería en el pago, cuando ya hay dinero de por medio.
    assertEquals(1, disponibleEn(EVENTO_PEQUENO));

    como(token)
        .body(Map.of("evento", EVENTO_PEQUENO, "butacas", 2, "asistente", "AST-0042"))
        .when()
        .post("/reservas")
        .then()
        .statusCode(409);

    como(token).when().delete("/reservas/{id}", deOtro).then().statusCode(204);
    assertEquals(antes, disponibleEn(EVENTO_PEQUENO));
  }

  @Test
  @DisplayName("SV-03 · Cinco reservas a la vez no venden más butacas de las que hay")
  void cincoALaVezNoSobrevenden() throws Exception {
    conElEventoEntero();
    String token = tokenDe(VENDEDOR);
    int disponible = disponibleEn(EVENTO_PEQUENO);

    // Esta es la única prueba concurrente de la suite, y lo es porque la
    // pregunta solo existe en concurrencia: cinco peticiones simultáneas
    // pidiendo una butaca cada una, con menos butacas que peticiones.
    //
    // Lo que NO se comprueba es cuáles ganan. Eso es una carrera y exigir un
    // ganador concreto daría una prueba que falla una de cada veinte veces.
    // Lo que se comprueba es la invariante: aceptadas <= disponibles.
    List<Callable<Integer>> intentos =
        java.util.stream.IntStream.range(0, 5)
            .<Callable<Integer>>mapToObj(
                i ->
                    () ->
                        como(token)
                            .body(
                                Map.of(
                                    "evento",
                                    EVENTO_PEQUENO,
                                    "butacas",
                                    1,
                                    "asistente",
                                    String.format("AST-01%02d", i)))
                            .when()
                            .post("/reservas")
                            .then()
                            .extract()
                            .statusCode())
            .toList();

    ExecutorService pool = Executors.newFixedThreadPool(5);
    List<Future<Integer>> resultados;
    try {
      resultados = pool.invokeAll(intentos);
    } finally {
      pool.shutdown();
    }

    int aceptadas = 0;
    for (Future<Integer> f : resultados) {
      int codigo = f.get();
      // O se acepta, o se rechaza por aforo. Cualquier otro código —un 500,
      // un 400— significaría que la concurrencia rompió algo distinto.
      assertTrue(
          codigo == 201 || codigo == 409,
          "Una petición concurrente devolvió " + codigo + ", que no es ni creada ni rechazada");
      if (codigo == 201) aceptadas++;
    }

    assertTrue(
        aceptadas <= disponible,
        "Se aceptaron " + aceptadas + " reservas con " + disponible + " butacas disponibles");
    org.hamcrest.MatcherAssert.assertThat(disponibleEn(EVENTO_PEQUENO), greaterThanOrEqualTo(0));
  }
}
