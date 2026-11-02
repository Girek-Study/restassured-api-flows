# 2. Diseño de casos

17 escenarios en cinco clases, una por pregunta. Este documento dice qué pregunta responde
cada grupo y **qué técnica usa**, porque en una suite de flujos la técnica decide si el caso
encuentra algo: la misma interrupción comprobada de dos maneras da un escenario que detecta
la fuga de aforo y un escenario que la deja pasar con un verde.

---

## El reparto

| Clase | Casos | Qué pregunta responde |
| --- | :-: | --- |
| [`FlujoCompletoTest`](../src/test/java/study/girek/flujos/FlujoCompletoTest.java) | FL-01, FL-02 | ¿El camino feliz mueve el aforo una sola vez? |
| [`InterrupcionDelFlujoTest`](../src/test/java/study/girek/flujos/InterrupcionDelFlujoTest.java) | IN-01 … IN-04 | **¿Qué queda cuando el flujo no termina?** |
| [`IdempotenciaTest`](../src/test/java/study/girek/flujos/IdempotenciaTest.java) | ID-01 … ID-03 | ¿Un reintento crea una segunda reserva? |
| [`ReglasDeEstadoTest`](../src/test/java/study/girek/flujos/ReglasDeEstadoTest.java) | ES-01 … ES-05 | ¿Los pasos en orden equivocado mueven el aforo? |
| [`SobreventaTest`](../src/test/java/study/girek/flujos/SobreventaTest.java) | SV-01 … SV-03 | ¿Se venden más butacas de las que hay? |

## Cuatro técnicas

### 1. Medir el aforo a los dos lados del flujo

Es la técnica que sostiene el repositorio, y es casi vergonzosamente simple:

```java
int antes = disponibleEn(EVENTO_GRANDE);
// ... el flujo, con su interrupción ...
assertEquals(antes, disponibleEn(EVENTO_GRANDE));
```

Lo que la hace valer no es cómo está escrita, es **dónde mira**. Un flujo interrumpido
responde con códigos que están todos bien: un 500 cuando revienta el cobro, un 409 cuando el
bloqueo ya caducó, un 204 cuando se anula. Una suite que juzgue por la respuesta da los tres
por correctos, porque lo son.

Y es **un delta, no un número fijo**. Nunca se comprueba que el disponible sea 50: se
comprueba que vuelva a ser el que era. Así el escenario sigue valiendo cuando otro escenario
venda butacas antes, y no hay que reiniciar el servidor entre pruebas.

#### IN-03 e IN-04, la pareja que lo explica todo

Son el mismo 500, con el mismo cuerpo, y son opuestos:

| | IN-03 · falla el pago | IN-04 · falla la emisión |
| --- | --- | --- |
| Respuesta | `500 ERROR_INTERNO` | `500 ERROR_INTERNO` |
| Estado tras el fallo | la reserva ya pasó a `PAGADA` | sigue `PAGADA`, intacta |
| Aforo correcto después | **vuelve**: el cliente cree que no compró | **sigue consumido**: está pagada |
| Qué puede hacer el cliente | nada, y se ha quedado sin butacas | reintentar la emisión |

Ningún escenario que lea solo la respuesta puede distinguirlos. La diferencia existe
únicamente en el aforo de después, y **esa es la razón de ser de una suite de flujos**.

IN-04 además comprueba que el reintento funciona, porque la mitad de «es un fallo
recuperable» es que se pueda recuperar de verdad.

### 2. Esperar a una condición, no a un reloj

Dos escenarios dependen de que caduque un bloqueo. La forma cómoda de escribirlos es
`Thread.sleep(4000)`, y es la forma equivocada por dos motivos a la vez: es **lenta cuando
acierta** —espera los cuatro segundos aunque el evento ocurra en uno— y es **mentirosa
cuando no**, porque si la máquina va justa el sleep termina antes y la prueba falla por el
reloj, no por la API.

```java
await()
    .atMost(Duration.ofSeconds(15))
    .pollInterval(Duration.ofMillis(300))
    .untilAsserted(() -> assertEquals(antes, disponibleEn(EVENTO_GRANDE)));
```

Hay un detalle que importa para leer el CI: cuando la condición no se cumple, Awaitility
lanza una `ConditionTimeoutException`, y surefire la cuenta en **`Errors`**, no en
`Failures`. Un CI que mire solo la columna de `Failures` ve un cero y concluye que la suite
no detecta nada, justo cuando sí lo detecta. El de este repositorio **suma las dos
columnas**, y eso está escrito en el workflow con su motivo.

#### Por qué IN-03 también espera

La reserva podría revertirse a `BLOQUEADA` y caducar, o liberarse en el acto. **Las dos son
correctas** y acaban en el mismo aforo. Exigir una de las dos sería imponer una
implementación desde la prueba, y es lo que convierte una suite en un freno: el día que
alguien cambie la compensación de forma legítima, el escenario se pone rojo sin que nada esté
mal.

Lo que se exige es el resultado: que el aforo vuelva, en un plazo razonable.

### 3. Concurrencia sin exigir un ganador

SV-03 lanza cinco peticiones simultáneas pidiendo una butaca cada una, con menos butacas que
peticiones. Es la única prueba concurrente de la suite, y lo es porque **la pregunta solo
existe en concurrencia**.

Lo que **no** comprueba es cuáles ganan. Eso es una carrera, y exigir un ganador concreto da
una prueba que falla una de cada veinte veces. Lo que comprueba es la invariante:

```java
assertTrue(aceptadas <= disponible);
```

Y una segunda cosa que suele faltar: que **todas** las respuestas sean 201 o 409. Un 500 bajo
concurrencia significa que la carrera rompió algo distinto, y sin esa comprobación pasaría
por «rechazada» sin más.

### 4. Comprobar que un error no escribió nada

Cinco escenarios mandan la petición mal a propósito, y ninguno se conforma con el código de
error:

```java
given().body(...).when().post("/reservas").then().statusCode(401);
assertEquals(antes, disponibleEn(EVENTO_GRANDE));   // ← esta línea es el caso
```

Un endpoint que valida la sesión **después** de escribir responde 401 y deja la reserva
hecha. El código de estado no lo delata. Lo mismo con el 409 de ES-02: pagar dos veces tiene
que ser rechazado **y no consumir otras dos butacas**, que es el doble cargo visto desde el
inventario y aparece en cuanto alguien pulsa dos veces el botón.

## Los 17, uno a uno

| Caso | Qué hace | Qué afirma |
| --- | --- | --- |
| **FL-01** | reserva, paga, emite | el aforo baja **una vez**, no una por paso |
| **FL-02** | reserva y consulta | la reserva trae `expiraEn`: sin él, el cliente no sabe cuánto tiempo tiene |
| **IN-01** | reserva y anula | el aforo vuelve entero, en el acto |
| **IN-02** | reserva y deja caducar | el aforo vuelve **solo**, sin que nadie pida nada |
| **IN-03** | reserva y el pago revienta | **el aforo tiene que volver** — aquí está el defecto |
| **IN-04** | paga y la emisión revienta | el aforo sigue consumido, y el reintento funciona |
| **ID-01** | dos POST con la misma clave | una sola reserva, y el aforo baja **una vez** |
| **ID-02** | dos POST sin clave | dos reservas distintas, y **eso no es un defecto** |
| **ID-03** | reintento con clave | devuelve el recurso completo, no un 409 «clave repetida» |
| **ES-01** | emitir sin pagar | 409, y la reserva sigue `BLOQUEADA` |
| **ES-02** | pagar dos veces | 409, y **no consume el doble** |
| **ES-03** | anular un billete emitido | 409: una devolución no entra por la puerta del bloqueo |
| **ES-04** | anular con rol auditor | **403 y no 401** |
| **ES-05** | reservar sin token | 401, **y no creó nada** |
| **SV-01** | pedir más de lo que queda | 409 que **dice cuántas quedan** |
| **SV-02** | bloqueo ajeno | el disponible cuenta bloqueos, no solo ventas |
| **SV-03** | cinco a la vez | aceptadas ≤ disponibles, y ninguna respuesta rara |

Tres merecen una nota.

**ID-02 no es un defecto, y por eso está.** Sin clave de idempotencia la API no tiene forma de
saber si la segunda petición es un reintento o una segunda compra del mismo asistente. El
caso fija esa frontera, porque es justo donde alguien propondría «deduplicar por contenido» y
rompería la compra legítima de dos entradas seguidas.

**ES-04 distingue 403 de 401.** El auditor está autenticado; lo que le falta es permiso.
Devolver 401 lo manda a renovar un token que está perfectamente bien, y el bucle de renovación
que sale de ahí es un clásico de los informes de incidencia.

**SV-01 exige que el error diga cuántas quedan.** No es un lujo: sin ese dato el cliente solo
puede reintentar a ciegas, bajando de uno en uno hasta acertar.

## El acoplamiento que hay, y qué se hizo con él

Las tres pruebas de `SobreventaTest` comparten el aforo del evento pequeño y dejan bloqueos
detrás que caducan solos. JUnit no garantiza el orden entre ellas.

La salida cómoda es `@Order`. Esconde el acoplamiento y lo deja listo para romperse el día
que alguien añada un caso en medio. Lo que hacen estas tres es **esperar a tener el evento
para sí**:

```java
private static void conElEventoEntero() {
  await().atMost(Duration.ofSeconds(15))
      .untilAsserted(() -> assertEquals(AFORO_PEQUENO, disponibleEn(EVENTO_PEQUENO)));
}
```

Ordenar las pruebas esconde el acoplamiento. Esperar lo resuelve.

---

Siguiente: [3. Matriz de trazabilidad](03-matriz-trazabilidad.md) — qué regla de negocio
cubre cada caso, y las que no cubre ninguno.
