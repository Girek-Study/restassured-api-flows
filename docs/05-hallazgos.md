# 5. Hallazgos

**Uno lo puse yo. Tres no.**

El primero es el defecto que la API de ejemplo trae de serie para demostrar que la suite
sirve: se apaga con `API_CORREGIDA=1`, y el CI exige que la suite lo detecte.

Los tres siguientes aparecieron montando esto. Dos de ellos habrían dejado el CI diciendo
exactamente lo contrario de lo que pasaba.

| Id | Resumen | Severidad | Origen |
| --- | --- | :-: | :-: |
| [F-01](#f-01) | Un pago que falla se queda con las butacas para siempre | **Crítica** | a propósito |
| [H-01](#h-01) | Los 17 escenarios fallaron a la vez por una dependencia que no estaba | Media | mío |
| [H-02](#h-02) | El CI habría dicho «la suite no detecta nada» justo cuando sí | **Alta** | mío |
| [H-03](#h-03) | Tres pruebas se pisaban el inventario según el orden | Media | mío |

---

## F-01

**Un pago que falla se queda con las butacas para siempre**

| | |
| --- | --- |
| **Dónde** | `POST /reservas/{id}/pago` |
| **Lo detecta** | **IN-03** |
| **Severidad** | Crítica |

El defecto entero cabe en el orden de dos líneas:

```js
reserva.estado = 'PAGADA';          // el aforo pasa de bloqueado a vendido
reserva.pagadaEn = ...;

if (elCobroRevienta) {
  return error(res, 500, 'ERROR_INTERNO', 'Falló el cobro');   // y nadie lo devuelve
}
```

El cliente recibe un **500** y da la compra por no hecha. Las butacas, en cambio, están
vendidas: ya no son un bloqueo, así que el barrido de caducidad —que solo mira las
bloqueadas— no las toca nunca.

Medido, con `disponible` antes y después del flujo:

```
aforo antes del flujo          disponible 10
POST /reservas (3 butacas)  →  201, disponible 7   (bloqueadas)
POST .../pago               →  500 ERROR_INTERNO
aforo después                  disponible 7        ← no vuelve
y pasado el bloqueo            disponible 7        ← tampoco
```

Son tres asientos que nadie compró y que nadie podrá comprar. Un evento de aforo 200 con un
uno por ciento de pagos fallidos pierde dos entradas por tanda y **nadie lo relaciona con la
pasarela**: el día del concierto sobran butacas vacías y en el informe de ventas no falta
nada.

Lo que lo hace invisible es que **ninguna respuesta por separado está mal**. El 500 es
correcto: el cobro falló de verdad. El aforo responde 200 con un número perfectamente
válido. Hay que comparar el aforo de antes con el de después, y eso solo lo hace quien prueba
flujos.

Así sale el rojo:

```
InterrupcionDelFlujoTest.siElPagoFallaElAforoVuelve » ConditionTimeout
  Un pago que falla no puede quedarse con las butacas.
  Son asientos que nadie compró y que nadie podrá comprar:
  el 500 le dijo al cliente que la operación no se hizo.
  ==> expected: <47> but was: <45> within 15 seconds.

Tests run: 17, Failures: 0, Errors: 1
BUILD FAILURE
```

Y con `API_CORREGIDA=1`, que revierte el estado antes de devolver el 500, la misma suite sin
tocar una línea:

```
Tests run: 17, Failures: 0, Errors: 0
BUILD SUCCESS
```

### Su opuesto, que responde igual

[IN-04](02-diseno-de-casos.md) hace fallar la **emisión** en lugar del pago. La respuesta es
el mismo 500 con el mismo cuerpo, y el comportamiento correcto es el contrario: la butaca
está pagada, consumirla está bien, y lo que procede es reintentar.

Un escenario que juzgue por la respuesta da los dos por iguales. **La diferencia existe
únicamente en el aforo de después**, y ese par de casos es la razón de ser de todo el
repositorio.

---

## H-01

**Los 17 escenarios fallaron a la vez por una dependencia que no estaba**

| | |
| --- | --- |
| **Cómo se vio** | La primera corrida: 17 de 17 en rojo, todos en la misma línea |
| **Severidad** | Media |

```
Tests run: 17, Failures: 0, Errors: 17, Skipped: 0

InterrupcionDelFlujoTest.anularDevuelveElAforo:29 -> BaseDeFlujo.tokenDe:56
  » IllegalState Cannot serialize object because no JSON serializer found in
    classpath. Please put Jackson (Databind), Gson, Johnzon, or Yasson in the classpath.
```

**RestAssured trae con qué leer JSON —`json-path`— pero no con qué escribirlo.** En cuanto
una petición manda un objeto como cuerpo hace falta un serializador, y no viene en la
dependencia principal.

No avisa al compilar. El código compila perfectamente, y el mensaje tampoco ayuda del todo:
dice que falta Jackson «en el classpath», que suena a problema de entorno, cuando lo que
falta es una línea del `pom.xml`.

**Corregido** declarándolo:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.18.4</version>
  <scope>test</scope>
</dependency>
```

Lo cuento porque el síntoma —**todo rojo de golpe, en la línea que abre sesión**— es idéntico
al de una API caída, y el primer reflejo es ir a mirar el servidor. El segundo, leer el
mensaje entero.

## H-02

**El CI habría dicho «la suite no detecta nada» justo cuando sí**

| | |
| --- | --- |
| **Cómo se vio** | Leyendo el resumen de la corrida que sí detectaba la fuga |
| **Medido** | `Tests run: 17, Failures: 0, Errors: 1` |
| **Severidad** | Alta |

El trabajo `detecta-la-fuga` exige **un** escenario en rojo, y lo contaba así:

```bash
FALLOS=$(... | grep -oE 'Failures: [0-9]+' | ...)
if [ "$FALLOS" != "1" ]; then exit 1; fi
```

El problema está en el resumen de arriba: `Failures: 0, Errors: 1`. La aserción de IN-03 vive
dentro de un `untilAsserted` de Awaitility, así que cuando no se cumple llega como
`ConditionTimeoutException` —una excepción— y **surefire la cuenta en `Errors`, no en
`Failures`**.

Con el recuento original, el trabajo habría leído un cero y habría fallado con este mensaje:

> `::error::Se esperaba 1 escenario en rojo y hubo 0.`

Es decir: **el CI habría acusado a la suite de no detectar nada, en la misma corrida en la
que la suite detectó exactamente lo que tenía que detectar.** Y el diagnóstico obvio ante ese
mensaje es «la aserción está mal, voy a reescribirla», que es como se desmonta un control
que funciona.

**Corregido** sumando las dos columnas, con el motivo escrito en el workflow:

```bash
F=$(echo "$RESUMEN" | grep -oE 'Failures: [0-9]+' | grep -oE '[0-9]+')
E=$(echo "$RESUMEN" | grep -oE 'Errors: [0-9]+' | grep -oE '[0-9]+')
FALLOS=$((F + E))
```

Y una comprobación de más que vino del mismo susto: que el escenario en rojo **sea IN-03**, y
no otro cualquiera que dé la casualidad de fallar. El recuento por sí solo no lo distingue.

```bash
if ! grep -q 'siElPagoFallaElAforoVuelve' corrida.log; then
  echo "::error::Falló un escenario, pero no es IN-03."
  exit 1
fi
```

De los cuatro hallazgos de este documento, este es el que más me interesa. **Un control de
calidad mal calibrado es peor que no tenerlo**: no solo deja pasar lo que debía frenar, sino
que acusa al mecanismo que sí funciona.

## H-03

**Tres pruebas se pisaban el inventario según el orden**

| | |
| --- | --- |
| **Cómo se vio** | Al escribir la tercera, releyendo las otras dos. No llegó a correr así |
| **Severidad** | Media |

Las tres pruebas de `SobreventaTest` comparten el aforo del evento pequeño —cuatro butacas— y
dos de ellas dejan bloqueos detrás que tardan tres segundos en caducar. JUnit **no garantiza
el orden** entre los métodos de una clase.

La segunda arrancaba con:

```java
int antes = disponibleEn(EVENTO_PEQUENO);
assertTrue(antes >= 2, "El evento pequeño debería tener sitio al empezar");
```

Si la de concurrencia corría justo antes, `antes` valía 1 o 0 y la prueba fallaba en su
primera línea, por un motivo que no tiene nada que ver con lo que comprueba. **Intermitente,
y con el fallo en el sitio equivocado.**

Lo apunto aunque no llegara a producir un rojo, por dos motivos. El primero es que lo vi
leyendo, no corriendo: habría pasado las primeras veces y habría empezado a fallar más
adelante, en la máquina de otro. El segundo es la corrección, que tenía dos opciones y la
cómoda era la mala:

```java
// la cómoda: @TestMethodOrder + @Order(1), @Order(2), @Order(3)
// la que está:
private static void conElEventoEntero() {
  await().atMost(Duration.ofSeconds(15))
      .untilAsserted(() -> assertEquals(AFORO_PEQUENO, disponibleEn(EVENTO_PEQUENO)));
}
```

**Ordenar las pruebas esconde el acoplamiento. Esperar lo resuelve.** Con `@Order`, el día
que alguien añada un caso en medio vuelve el mismo fallo intermitente y ya nadie recuerda por
qué estaba el orden.

---

## Lo que se lleva de aquí

Dos de los tres hallazgos propios **no producían un rojo**: producían o un rojo mal
atribuido, o un verde que iba a romperse más adelante en la máquina de otro.

| Lo que habría dicho el informe | Lo que pasaba de verdad |
| --- | --- |
| «La suite no detecta nada» ([H-02](#h-02)) | La suite detectaba exactamente lo que debía |
| 17 de 17 en rojo, parece la API caída ([H-01](#h-01)) | Faltaba una línea del `pom.xml` |
| Verde ([H-03](#h-03)) | Verde **de momento**, y en orden alfabético |

Y el que sí era de la API —[F-01](#f-01)— no se veía en ninguna respuesta. Las tres del
flujo estaban bien: un 201, un 500 legítimo y un aforo que respondía 200 con un número
válido.

Por eso el CI de este repositorio tiene dos trabajos y no uno. La pregunta que contesta el
primero no es «¿pasa la suite?», es:

> ¿Esta suite **habría sido capaz** de detectar el problema que dice que no existe?

Y por eso ese trabajo, además de contar los rojos, comprueba **cuál** está rojo. Un control
que no sabe distinguir un acierto de una casualidad no es un control.
