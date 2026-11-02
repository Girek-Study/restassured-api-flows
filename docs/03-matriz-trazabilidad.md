# 3. Matriz de trazabilidad

Una regla de negocio por fila. La columna de la derecha dice qué escenario la comprueba, y
**cinco filas no tienen ninguno**: están aquí con su motivo, que es la única razón por la que
una matriz sirve para algo.

A diferencia del repositorio [`karate-api-contract`](https://github.com/Girek-Study/karate-api-contract),
aquí no hay un contrato OpenAPI que haga de oráculo. El oráculo es la **invariante de aforo**,
y cada fila se puede leer como una afirmación sobre ella.

---

## Reglas de aforo

| # | La regla | Caso |
| --- | --- | :-: |
| AF-1 | `disponible` = total − bloqueadas − vendidas | SV-02 |
| AF-2 | Un bloqueo reduce el disponible aunque no esté pagado | SV-02 |
| AF-3 | Pagar **no** vuelve a reducir el disponible: mueve butacas de bloqueadas a vendidas | **FL-01** |
| AF-4 | Emitir el billete no toca el aforo | FL-01 |
| AF-5 | No se aceptan más butacas de las disponibles | SV-01 |
| AF-6 | Ni siquiera con peticiones simultáneas | SV-03 |
| AF-7 | Un rechazo por aforo dice cuántas butacas quedan | SV-01 |
| AF-8 | El disponible nunca es negativo | SV-03 |

AF-3 es la fila que más se incumple en una API real y la que menos se prueba. Si el pago
vuelve a descontar, el aforo baja el doble por cada venta **y nadie lo nota hasta que el
evento se agota con la mitad de las butacas vacías**.

## El flujo interrumpido

| # | La regla | Caso |
| --- | --- | :-: |
| IT-1 | Anular un bloqueo devuelve las butacas en el acto | IN-01 |
| IT-2 | Un bloqueo caducado devuelve las butacas **solo** | IN-02 |
| IT-3 | **Un pago que falla devuelve las butacas** | **IN-03** |
| IT-4 | Una emisión que falla **no** devuelve las butacas: están pagadas | IN-04 |
| IT-5 | Una emisión que falló se puede reintentar | IN-04 |
| IT-6 | El cliente puede saber cuánto le queda para pagar | FL-02 |
| IT-7 | Un pago que falla deja un rastro que permita conciliar con la pasarela | — ⬇ |

**IT-3 es el defecto.** La API de ejemplo lo incumple, y es el único escenario que la suite
tiene en rojo a propósito.

**IT-7 no tiene caso.** Para conciliar con una pasarela de cobro haría falta que exista una
pasarela, y aquí no la hay. Montar la integración para poder probarla sería escribir el
sistema a medida de la prueba, y la prueba dejaría de demostrar nada sobre el sistema real.

## Reintentos

| # | La regla | Caso |
| --- | --- | :-: |
| RE-1 | Dos POST con la misma `Idempotency-Key` crean **una** reserva | ID-01 |
| RE-2 | El reintento responde **200**, no 201: no se creó nada nuevo | ID-01, ID-03 |
| RE-3 | El reintento devuelve el recurso completo, no un 409 | ID-03 |
| RE-4 | El reintento se marca como tal (`idempotent-replay`) | ID-01 |
| RE-5 | Sin clave, dos peticiones iguales son dos reservas | ID-02 |
| RE-6 | Un reintento no consume aforo dos veces | **ID-01** |
| RE-7 | La clave caduca después de un plazo | — ⬇ |
| RE-8 | La misma clave con un cuerpo distinto se rechaza | — ⬇ |

**RE-7 y RE-8 no tienen caso, y las dos por el mismo motivo**: la API de ejemplo no declara
ninguna de las dos cosas. Son buenas prácticas reconocidas —la segunda está en el borrador de
idempotencia del IETF— pero exigirlas desde la prueba sería **inventar un requisito**. Lo que
corresponde es pedirlas en el diseño; mientras no estén, las filas se quedan sin cubrir y
dicho.

RE-6 merece su negrita: es la que convierte ID-01 en un caso de verdad. Comparar los dos
identificadores no basta —podrían coincidir por otro motivo— y lo que le duele al negocio es
el aforo, no el id.

## Orden de los pasos y permisos

| # | La regla | Caso |
| --- | --- | :-: |
| OR-1 | No se emite sin pagar | ES-01 |
| OR-2 | No se paga dos veces | ES-02 |
| OR-3 | Un paso rechazado **no mueve el aforo** | ES-01, ES-02, ES-05 |
| OR-4 | Un billete emitido no se anula por la vía del bloqueo | ES-03 |
| OR-5 | Anular exige el rol `VENDEDOR`: **403**, no 401 | ES-04 |
| OR-6 | Sin token no se reserva, **y no se crea nada** | ES-05 |
| OR-7 | No se paga un bloqueo caducado | — ⬇ |
| OR-8 | Un `GET` de reserva ajena no debería verse | — ⬇ |

**OR-7 no tiene caso propio** porque la API ya lo responde con un 409 `RESERVA_EXPIRADA` y
porque el escenario que lo probaría tendría que esperar la caducidad para después pagar: es
IN-02 con un paso más y no añade información sobre el aforo, que es lo que esta suite mide.

**OR-8 no tiene caso, y es el hueco más incómodo de esta matriz.** La API de ejemplo no tiene
el concepto de «reserva de otro»: cualquier sesión ve cualquier reserva. Comprobar un IDOR
exigiría que el modelo tuviera dueños, y eso es un cambio de diseño, no un caso de prueba.
Queda nombrado aquí porque **un hueco de autorización que nadie escribe es un hueco que nadie
recuerda**.

---

## El recuento

| | Filas | Con caso | Sin caso |
| --- | :-: | :-: | :-: |
| Aforo | 8 | 8 | 0 |
| Flujo interrumpido | 7 | 6 | 1 |
| Reintentos | 8 | 6 | 2 |
| Orden y permisos | 8 | 6 | 2 |
| **Total** | **31** | **26** | **5** |

26 de 31. De las cinco que faltan:

- **Dos** porque la API no declara la regla (RE-7, RE-8). Exigirla sería inventar un
  requisito, y el escenario se borraría el día que alguien implemente la regla de otra forma
  igual de válida.
- **Una** porque exigiría montar una integración que no existe (IT-7).
- **Una** porque es redundante con un caso que ya está (OR-7).
- **Una** porque el modelo de la API no tiene el concepto que haría falta (OR-8), y eso se
  arregla en el diseño.

Ninguna se cubre «por si acaso». Un escenario que exige algo que el sistema no promete no
protege nada: **frena el día que el sistema cambie de forma legítima**, y lo que hace el
equipo entonces es borrarlo. Después de borrar dos o tres, deja de leer los demás.

## La fila en negrita

**IT-3** es la única regla que la API de ejemplo incumple, y la única que la suite tiene en
rojo. Las demás 25 están en verde las dos veces que corre el CI.

Si esta matriz solo tuviera las otras 30 filas, la suite estaría **verde entera** contra una
API que pierde butacas cada vez que una pasarela de pago tiene un mal rato.

---

Siguiente: [4. Análisis de riesgos](04-analisis-riesgos.md) — los cinco riesgos de una suite
de flujos, y el primero es que se vuelva intermitente.
