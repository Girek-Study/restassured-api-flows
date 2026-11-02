# 1. Análisis de flujos

Una prueba de API comprueba una respuesta. Una prueba de flujo comprueba **qué queda
después**, y eso no está en ninguna respuesta.

Este documento dice qué flujo se prueba, cuál es la invariante que lo juzga, cuáles son las
cuatro formas de no llegar al final, y qué se queda fuera con su motivo.

---

## El flujo

Tres pasos, cada uno con su endpoint, y una reserva que cambia de estado en cada uno:

```
                POST /reservas            POST .../pago          POST .../emision
   (nada)  ────────────────────▶ BLOQUEADA ──────────▶ PAGADA ──────────────▶ EMITIDA
                                     │                   │
                     DELETE          │                   │  la emisión falla:
                     o caducidad     ▼                   ▼  se reintenta, no se pierde nada
                               ANULADA / EXPIRADA    (sin salida)
```

Cada paso mueve butacas, y las mueve de forma distinta:

| Paso | Estado | Qué le hace al aforo |
| --- | --- | --- |
| `POST /reservas` | `BLOQUEADA` | **bloquea** butacas: nadie más puede cogerlas, pero no están vendidas |
| `POST /reservas/{id}/pago` | `PAGADA` | las **vende**: pasan de bloqueadas a vendidas, y el disponible no se mueve |
| `POST /reservas/{id}/emision` | `EMITIDA` | emite el billete. **No toca el aforo** |
| `DELETE /reservas/{id}` | `ANULADA` | **devuelve** las butacas bloqueadas |
| (caducidad) | `EXPIRADA` | las devuelve **sola**, sin que nadie pida nada |

La diferencia entre bloquear y vender es el eje de todo. Un bloqueo caduca solo; una venta no.
Si un flujo roto deja butacas bloqueadas, el sistema se recupera en segundos. Si las deja
vendidas, **no se recupera nunca**.

## La invariante

Hay un solo número que juzga todos los escenarios de esta suite:

> **`disponible` = aforo total − bloqueadas − vendidas**

Y una regla sobre él:

> Un flujo que no termina tiene que devolver `disponible` al valor que tenía antes de
> empezar.

Eso es lo que se mide: el aforo de antes contra el aforo de después, a los dos lados del
flujo. No el código de respuesta del paso que falló.

La diferencia no es de estilo. Un flujo interrumpido responde con códigos que están todos
bien: un **500** cuando revienta el cobro, un **409** cuando el bloqueo ya caducó, un **204**
cuando se anula. Una suite que juzgue por la respuesta da los tres por correctos, porque lo
son. Lo que no está bien es lo que queda detrás de uno de ellos.

## Las cuatro formas de no llegar al final

| Salida | Cómo ocurre en producción | Qué debe pasar con el aforo |
| --- | --- | --- |
| **Anulación** | El usuario cierra la pestaña y el front anula | Vuelve entero, en el acto |
| **Caducidad** | El usuario se va a comer con el pago a medias | Vuelve solo, sin que nadie pida nada |
| **Fallo en el pago** | La pasarela tiene un mal rato de madrugada | **Vuelve** — y aquí está el defecto |
| **Fallo en la emisión** | La impresora de billetes no responde | **No vuelve**, y está bien: está pagada |

Las dos últimas responden **500 con el mismo cuerpo**. Son opuestas:

```json
{ "error": { "codigo": "ERROR_INTERNO", "detalle": "Falló el cobro" } }
{ "error": { "codigo": "ERROR_INTERNO", "detalle": "Falló la emisión del billete" } }
```

En el primer caso el cliente recibió un 500 y da la compra por no hecha: las butacas tienen
que volver. En el segundo la butaca está pagada, así que consumirla es correcto, y lo que
procede es reintentar la emisión.

**Un escenario que solo mire el código de respuesta no puede distinguirlos.** Los dos son
500. La diferencia existe únicamente en el aforo de después, y por eso los dos casos —
[IN-03 e IN-04](02-diseno-de-casos.md) — son la pareja más importante de la suite.

## Qué puede salir mal que no es el camino feliz

Además del flujo roto, hay tres familias de problema que una prueba de un solo endpoint no
alcanza:

**El reintento.** Un cliente que no recibe la respuesta de un `POST` no sabe si la operación
ocurrió. Lo que hace es reintentar: una red que corta, un balanceador que cierra la conexión,
un móvil que cambia de antena. Si la API no distingue el reintento del segundo intento, el
usuario acaba con dos reservas y el aforo baja el doble. Se resuelve con `Idempotency-Key`, y
se comprueba en [ID-01 a ID-03](02-diseno-de-casos.md).

**Los pasos en el orden equivocado.** Un flujo de tres pasos tiene seis órdenes posibles y
solo uno es el bueno. Los otros cinco los pide alguien: dos pestañas abiertas, un reintento
que llega tarde, un script escrito mirando otra versión de la documentación. La aserción que
importa en todos es la misma: **el estado equivocado no mueve el aforo**. Un 409 que además
consume butacas es peor que un 500.

**La concurrencia sobre el mismo inventario.** Cinco peticiones a la vez, menos butacas que
peticiones. Es la única pregunta de esta suite que no existe fuera de la concurrencia.

## Lo que no se prueba, y por qué

| Lo que falta | Por qué |
| --- | --- |
| Que el cobro llegue a la pasarela | No hay pasarela. Montarla para probarla sería escribir la integración a medida de la prueba |
| Reintento del paso de **pago** con clave de idempotencia | La API solo declara idempotencia en la creación. Exigirla en el pago sería inventar un requisito |
| Qué cliente gana una carrera por la última butaca | Es una carrera. Exigir un ganador concreto da una prueba que falla una de cada veinte veces. Lo que se comprueba es la invariante: aceptadas ≤ disponibles |
| El rendimiento del flujo bajo carga | Otro repositorio: [`k6-performance-suite`](https://github.com/Girek-Study/k6-performance-suite) |
| Que la respuesta cumpla un contrato OpenAPI | Otro repositorio: [`karate-api-contract`](https://github.com/Girek-Study/karate-api-contract) |

Las dos últimas filas importan para entender qué es esto. **Aquí no se comprueba si la
respuesta es la prometida**; se comprueba qué deja el sistema cuando la secuencia se tuerce.
Son dos preguntas distintas y hacen falta las dos.

---

Siguiente: [2. Diseño de casos](02-diseno-de-casos.md) — los 17 escenarios y las cuatro
técnicas que los sostienen.
