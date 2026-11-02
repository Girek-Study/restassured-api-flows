# El trabajo de calidad, antes del código

Un repositorio de pruebas de API enseña **cómo** se encadenan peticiones. No enseña qué
número juzga el resultado, cuáles son las formas de que un flujo no termine, qué regla de
negocio no cubre ningún escenario, ni cómo se sabe que la suite no está pasando en vano.
Esta carpeta es esa parte.

| Documento | Qué contiene |
| --- | --- |
| [1. Análisis de flujos](01-analisis-de-flujos.md) | El flujo de tres pasos, la **invariante de aforo** que lo juzga, y las cuatro formas de no llegar al final |
| [2. Diseño de casos](02-diseno-de-casos.md) | Los 17 escenarios, las cuatro técnicas, y la pareja de casos que responde igual y significa lo contrario |
| [3. Matriz de trazabilidad](03-matriz-trazabilidad.md) | 31 reglas de negocio, 26 con caso — y las **5 que no tienen ninguno**, con su motivo |
| [4. Análisis de riesgos](04-analisis-riesgos.md) | Cinco riesgos. El primero es que la suite se vuelva intermitente |
| [5. Hallazgos](05-hallazgos.md) | **Cuatro.** Uno lo puse yo. Tres no |

---

## La pregunta de este repositorio

No es «¿la respuesta es la que prometió el contrato?». Esa es la de
[`karate-api-contract`](https://github.com/Girek-Study/karate-api-contract). Aquí es:

> **¿Qué deja el sistema cuando el flujo se rompe a la mitad?**

Un flujo de tres pasos tiene cuatro formas de no llegar al final, y **las cuatro responden
con códigos que están bien**: un 204 al anular, un 409 cuando el bloqueo caducó, y dos 500
—uno en el pago, otro en la emisión— con el mismo cuerpo.

Esos dos 500 significan lo contrario el uno del otro:

| | falla el pago | falla la emisión |
| --- | --- | --- |
| Respuesta | `500 ERROR_INTERNO` | `500 ERROR_INTERNO` |
| Lo correcto | **el aforo vuelve** | el aforo sigue consumido |
| Por qué | el cliente cree que no compró | la butaca está pagada |

**Ningún escenario que mire la respuesta puede distinguirlos.** La diferencia existe
únicamente en el estado de después, y por eso todas las aserciones de esta suite miran el
mismo número: las butacas que de verdad se pueden vender, antes y después del flujo.

## El resultado medido

El CI corre la suite dos veces contra la misma API, en dos modos:

| Trabajo | API | Exige | Resultado medido |
| --- | --- | --- | --- |
| `detecta-la-fuga` | sin compensación | que falle, **1 rojo, y que sea IN-03** | 17 escenarios, **1 rojo**, FAILURE |
| `no-miente` | con compensación | que pase entera | 17 escenarios, **0 rojos**, SUCCESS |

El defecto, medido con el aforo a los dos lados del flujo:

```
aforo antes del flujo          disponible 10
POST /reservas (3 butacas)  →  201, disponible 7   (bloqueadas)
POST .../pago               →  500 ERROR_INTERNO
aforo después                  disponible 7        ← no vuelve
y pasado el bloqueo            disponible 7        ← tampoco
```

Tres asientos que nadie compró y que nadie podrá comprar. En un evento de aforo 200 con un
uno por ciento de pagos fallidos son dos entradas por tanda, y **nadie lo relaciona con la
pasarela**: el día del concierto sobran butacas vacías y en el informe de ventas no falta
nada.

## El hallazgo que más me interesa no es ese

Es [H-02](05-hallazgos.md#h-02), y no está en la API: está en el control que la vigila.

La aserción de IN-03 vive dentro de un `untilAsserted` de Awaitility, así que cuando falla
llega como excepción y **surefire la cuenta en `Errors`, no en `Failures`**. El CI contaba
solo la columna de `Failures`, leía un cero, y habría fallado diciendo:

> `::error::Se esperaba 1 escenario en rojo y hubo 0.`

Es decir: **el CI habría acusado a la suite de no detectar nada, en la misma corrida en la
que detectó exactamente lo que tenía que detectar.** Y el diagnóstico obvio ante ese mensaje
—«la aserción está mal, la reescribo»— es cómo se desmonta un control que funciona.

Está corregido sumando las dos columnas, y con una comprobación de más: que el escenario en
rojo **sea IN-03** y no otro que dé la casualidad de fallar. Un control que no distingue un
acierto de una casualidad no es un control.

## Y cinco reglas que no tienen caso

La matriz tiene 31 reglas de negocio y **cinco siguen sin escenario** después de escribirla:

- **Dos** porque la API no declara la regla —caducidad de la clave de idempotencia, misma
  clave con cuerpo distinto—. Son buenas prácticas reconocidas, pero exigirlas desde la
  prueba sería inventar un requisito.
- **Una** porque haría falta montar una pasarela de cobro que no existe.
- **Una** por redundante con un caso que ya está.
- **Una** porque el modelo de la API no tiene el concepto que haría falta: no hay «reserva de
  otro», así que no se puede comprobar un IDOR. **Está nombrada porque un hueco de
  autorización que nadie escribe es un hueco que nadie recuerda.**

Cubrirlas «por si acaso» habría dejado la matriz en 31 de 31 y la suite peor: un escenario
que exige algo que el sistema no promete frena el día que el sistema cambie de forma
legítima, y lo que hace el equipo entonces es borrarlo.

---

Volver al [README del repositorio](../README.md).
