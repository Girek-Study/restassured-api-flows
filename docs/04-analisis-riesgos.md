# 4. Análisis de riesgos

El riesgo de una suite de flujos no es que falle. Es que **se vuelva intermitente**, porque
una suite intermitente se acaba relanzando hasta que pase, y a partir de ese día ya no
informa de nada aunque esté perfectamente escrita.

Cinco riesgos, dos de ellos abiertos.

---

## R-01 · Los escenarios comparten el aforo y se pisan

**Severidad: alta. Mitigado, y pasó de verdad.**

Todos los escenarios de esta suite tocan el mismo inventario. Si dos corren a la vez sobre el
mismo evento, se restan butacas entre ellos y **el fallo aparece en el escenario
equivocado**: el que mide pierde, y el que consumió ni se entera.

Tres cosas lo tapan, y las tres hacen falta:

**1. Suite secuencial.** `<parallel>none</parallel>` en el `pom.xml`, con el motivo escrito
al lado. Lo que se ahorraría paralelizando son segundos; lo que costaría es una suite que
falla a veces.

**2. Aserciones sobre el delta, nunca sobre un número fijo.**

```java
int antes = disponibleEn(EVENTO_GRANDE);
// ...
assertEquals(antes, disponibleEn(EVENTO_GRANDE));
```

Nunca se comprueba que el disponible sea 50. Así un escenario que venda butacas no rompe a
los demás, y no hace falta reiniciar el servidor entre pruebas —que es la mitigación que
funciona en el equipo de quien la escribe y falla en el de todos los demás—.

**3. Espera al estado inicial donde el delta no basta.** Las tres pruebas de sobreventa
necesitan el evento pequeño entero, y JUnit no garantiza su orden:

```java
private static void conElEventoEntero() {
  await().atMost(Duration.ofSeconds(15))
      .untilAsserted(() -> assertEquals(AFORO_PEQUENO, disponibleEn(EVENTO_PEQUENO)));
}
```

La salida cómoda habría sido `@Order`. **Ordenar las pruebas esconde el acoplamiento;
esperar lo resuelve**, y además deja la suite lista para el día que alguien añada un caso en
medio.

## R-02 · Una espera mal hecha convierte la suite en una tirada de dados

**Severidad: alta. Mitigado.**

Dos escenarios dependen de que caduque un bloqueo. Escritos con `Thread.sleep(4000)` serían
**lentos cuando aciertan** —esperan cuatro segundos aunque el evento ocurra en uno— y
**mentirosos cuando no**: en una máquina cargada el sleep termina antes que el evento y la
prueba falla por el reloj, no por la API.

Y el fallo por reloj es el peor de todos, porque se arregla relanzando. La segunda vez pasa,
nadie investiga, y la suite ha aprendido a mentir.

**Qué lo tapa:** Awaitility sondea hasta que la condición se cumple o se agota el plazo.

```java
await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
```

Hay una consecuencia que afecta al CI y conviene saber: cuando la condición no se cumple,
Awaitility lanza `ConditionTimeoutException`, y surefire la cuenta en **`Errors`**, no en
`Failures`. El CI de este repositorio **suma las dos columnas**; mirar solo una daría cero y
el trabajo se pondría rojo diciendo que la suite no detecta nada justo cuando sí lo hace.

## R-03 · Un escenario puede exigir una implementación en vez de un resultado

**Severidad: media. Mitigado, con criterio escrito.**

IN-03 pregunta si el aforo vuelve después de un pago que falla. Hay **dos formas correctas**
de que vuelva: revertir la reserva a `BLOQUEADA` y dejar que caduque, o liberarla en el acto.

Exigir una de las dos convertiría el escenario en un freno. El día que alguien cambie la
compensación de forma perfectamente legítima, la suite se pone roja sin que nada esté mal, y
lo que hace el equipo es editar la prueba para que vuelva a pasar. Después de hacer eso dos
veces, nadie se cree el rojo siguiente.

**Lo que se exige es el resultado**: que el aforo vuelva, en un plazo razonable. Por eso IN-03
espera en vez de comprobar al instante.

El mismo criterio aparece en SV-03, que comprueba la invariante `aceptadas <= disponibles` y
**no cuáles de las cinco peticiones ganan**. Exigir un ganador concreto en una carrera da una
prueba que falla una de cada veinte veces.

## R-04 · La suite puede pasar sin comprobar nada

**Severidad: alta. Mitigado con el CI.**

Es el riesgo que comparten todas las suites y el que casi nadie mide: un verde de una suite
vacía y un verde de una suite completa son el mismo pixel.

| Trabajo del CI | API | Exige |
| --- | --- | --- |
| `detecta-la-fuga` | sin compensación | que **falle**, con **un** escenario en rojo, **y que sea IN-03** |
| `no-miente` | con compensación | que **pase entera** |

El primero hace tres comprobaciones y las tres hacen falta:

1. Que el build falle. Si pasa, la suite no está mirando el aforo.
2. Que falle **una sola vez**. Dos significa que hay un defecto nuevo sin mirar; cero, que
   alguien debilitó la aserción.
3. Que el escenario en rojo **sea IN-03**. Un rojo en el sitio equivocado también es una
   suite rota, y el recuento por sí solo no lo distingue.

```bash
if ! grep -q 'siElPagoFallaElAforoVuelve' corrida.log; then
  echo "::error::Falló un escenario, pero no es IN-03."
  exit 1
fi
```

## R-05 · El estado en memoria se acumula entre corridas

**Severidad: baja. Aceptado, con el diseño que lo hace irrelevante.**

La API de ejemplo guarda todo en memoria y su contenedor sobrevive entre `docker compose
run`. Las butacas vendidas por una corrida siguen vendidas en la siguiente: el evento grande
tiene 50 butacas y cada corrida consume unas pocas.

No se mitiga reiniciando el servidor, a propósito. Se mitiga con dos decisiones que ya están:
**las aserciones son deltas** y **el evento grande tiene sitio de sobra**. Una suite que
exige un estado inicial exacto acaba exigiéndolo también en el entorno de alguien que no
puede reiniciar nada, y ese alguien es siempre el que más necesita correrla.

Queda abierto que una máquina que corra la suite **decenas de veces seguidas** sin reiniciar
el contenedor acabará agotando el evento grande. El síntoma sería claro —un 409
`AFORO_INSUFICIENTE` en la creación— y la solución, un `docker compose down`. Está dicho aquí
en vez de resuelto con una API que se reinicie sola, porque una API que se reinicia sola no
se parece a ninguna API real.

---

## Resumen

| Id | Riesgo | Severidad | Estado |
| --- | --- | :-: | --- |
| [R-01](#r-01--los-escenarios-comparten-el-aforo-y-se-pisan) | Los escenarios se pisan el inventario | **Alta** | Mitigado: secuencial, deltas y espera al estado inicial |
| [R-02](#r-02--una-espera-mal-hecha-convierte-la-suite-en-una-tirada-de-dados) | Esperas por reloj | **Alta** | Mitigado con Awaitility |
| [R-03](#r-03--un-escenario-puede-exigir-una-implementación-en-vez-de-un-resultado) | Exigir implementación en vez de resultado | Media | Mitigado, con criterio escrito |
| [R-04](#r-04--la-suite-puede-pasar-sin-comprobar-nada) | Pasar sin comprobar nada | **Alta** | Mitigado con `detecta-la-fuga` |
| [R-05](#r-05--el-estado-en-memoria-se-acumula-entre-corridas) | Estado acumulado entre corridas | Baja | **Aceptado**, con el síntoma dicho |

---

Siguiente: [5. Hallazgos](05-hallazgos.md) — uno lo puse yo. Tres no.
