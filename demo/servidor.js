// ============================================================================
//  API de reservas de entradas. Node y nada más: sin dependencias, sin build.
//
//  Existe para probar FLUJOS, no respuestas sueltas. Una reserva pasa por tres
//  pasos y cada uno mueve aforo:
//
//      POST /reservas                 BLOQUEADA   aforo bloqueado
//      POST /reservas/{id}/pago       PAGADA      aforo consumido
//      POST /reservas/{id}/emision    EMITIDA     billete emitido
//
//  Y hay tres formas de salir del flujo sin terminarlo: anular, dejar que el
//  bloqueo expire, o que un paso falle a la mitad. Las tres tienen que
//  devolver el aforo. La tercera NO lo devuelve, y ese es el defecto que este
//  repositorio existe para enseñar.
//
//  El fallo se pide con una cabecera, no con una variable de entorno:
//
//      x-fallo-en: pago      el cobro revienta DESPUÉS de consumir el aforo
//      x-fallo-en: emision   la emisión revienta sin haber tocado el estado
//
//  Se dispara por petición y no por proceso a propósito: así el fallo ocurre
//  solo en el escenario que lo pide, y los demás no heredan un servidor roto.
//  Una inyección global obliga a dos corridas de la suite entera para probar
//  dos cosas, y los fallos de una tanda se mezclan con los de la otra.
//
//  API_CORREGIDA=1 activa la compensación: el pago revierte el estado antes
//  de devolver el 500. Con eso, el mismo fallo deja de perder aforo.
//
//  Ningún dato real. Los asistentes son correlativos propios (AST-0001), no
//  documentos de identidad.
// ============================================================================

const http = require('node:http');
const { randomUUID } = require('node:crypto');

const CORREGIDA = process.env.API_CORREGIDA === '1';

// 8095 y no 8080. El 8080 es el puerto más ocupado de cualquier equipo de
// desarrollo: en el equipo donde se escribió esto lo tenía un Adminer, que
// responde 200 con HTML y hace que las primeras comprobaciones vayan contra
// otro servicio sin que nada lo diga.
const PUERTO = Number(process.env.PUERTO ?? 8095);

// Cuánto dura el bloqueo de una reserva sin pagar. Corto a propósito: una
// prueba de expiración que tarda quince minutos no se ejecuta nunca, y una
// suite que no se ejecuta no informa de nada.
const SEGUNDOS_DE_BLOQUEO = Number(process.env.SEGUNDOS_DE_BLOQUEO ?? 3);

// Dos eventos con aforos muy distintos a propósito.
//
// El grande es el campo de pruebas de los flujos: tiene sitio de sobra para
// que ningún escenario se quede sin butacas por culpa del anterior. El
// pequeño existe solo para probar la sobreventa, que necesita un aforo que se
// pueda agotar en una petición.
const EVENTOS = new Map([
  ['EVT-001', { id: 'EVT-001', nombre: 'Concierto de apertura', aforo: 50 }],
  ['EVT-002', { id: 'EVT-002', nombre: 'Charla de cierre', aforo: 4 }],
]);

const USUARIOS = [
  { usuario: 'taquilla', clave: 'taquilla-2026', rol: 'VENDEDOR' },
  { usuario: 'auditoria', clave: 'auditoria-2026', rol: 'AUDITOR' },
];

const reservas = new Map();
const sesiones = new Map();
/** Clave de idempotencia -> id de la reserva que creó. */
const idempotencia = new Map();

let siguienteId = 0;

function nuevoId() {
  siguienteId += 1;
  return `RES-${String(siguienteId).padStart(4, '0')}`;
}

// --- Aforo ------------------------------------------------------------------

/**
 * Libera las reservas bloqueadas que ya pasaron su `expiraEn`.
 *
 * Se llama antes de CUALQUIER lectura o escritura de aforo, y no desde un
 * temporizador. Un temporizador deja el aforo correcto «dentro de un rato», y
 * una prueba que consulta justo antes del tic ve un número que no es ni el de
 * antes ni el de después. La caducidad se resuelve al leer.
 */
function barrerExpiradas() {
  const ahora = Date.now();
  for (const r of reservas.values()) {
    if (r.estado === 'BLOQUEADA' && Date.parse(r.expiraEn) <= ahora) {
      r.estado = 'EXPIRADA';
    }
  }
}

/** Aforo de un evento, desglosado. `disponible` es lo que de verdad se puede vender. */
function aforoDe(idEvento) {
  barrerExpiradas();
  const evento = EVENTOS.get(idEvento);
  let bloqueadas = 0;
  let vendidas = 0;
  for (const r of reservas.values()) {
    if (r.evento !== idEvento) continue;
    if (r.estado === 'BLOQUEADA') bloqueadas += r.butacas;
    if (r.estado === 'PAGADA' || r.estado === 'EMITIDA') vendidas += r.butacas;
  }
  return {
    evento: idEvento,
    total: evento.aforo,
    bloqueado: bloqueadas,
    vendido: vendidas,
    disponible: evento.aforo - bloqueadas - vendidas,
  };
}

// --- Utilidades -------------------------------------------------------------

function responder(res, codigo, cuerpo, cabeceras = {}) {
  const texto = cuerpo === undefined ? '' : JSON.stringify(cuerpo);
  res.writeHead(codigo, {
    'content-type': 'application/json; charset=utf-8',
    ...cabeceras,
  });
  res.end(texto);
}

function error(res, codigo, clave, detalle, extra = {}) {
  responder(res, codigo, { error: { codigo: clave, detalle, ...extra } });
}

function sesionDe(req) {
  const cabecera = req.headers.authorization ?? '';
  if (!cabecera.startsWith('Bearer ')) return null;
  return sesiones.get(cabecera.slice(7)) ?? null;
}

// --- Rutas ------------------------------------------------------------------

const rutas = [
  {
    metodo: 'GET',
    patron: /^\/salud$/,
    publica: true,
    manejar(_req, res) {
      responder(res, 200, {
        estado: 'arriba',
        corregida: CORREGIDA,
        segundosDeBloqueo: SEGUNDOS_DE_BLOQUEO,
      });
    },
  },

  {
    metodo: 'POST',
    patron: /^\/sesiones$/,
    publica: true,
    manejar(_req, res, _m, cuerpo) {
      const encontrado = USUARIOS.find(
        (u) => u.usuario === cuerpo.usuario && u.clave === cuerpo.clave,
      );
      // El mismo error para clave mala y para usuario inexistente: si se
      // distinguen, cualquiera enumera usuarios válidos a base de intentos.
      if (!encontrado) {
        return error(res, 401, 'CREDENCIALES_INVALIDAS', 'Usuario o clave incorrectos');
      }
      const token = randomUUID();
      sesiones.set(token, { usuario: encontrado.usuario, rol: encontrado.rol });
      responder(res, 201, { token, rol: encontrado.rol });
    },
  },

  {
    metodo: 'GET',
    patron: /^\/eventos\/([A-Z]{3}-\d{3})\/aforo$/,
    publica: true,
    manejar(_req, res, m) {
      if (!EVENTOS.has(m[1])) {
        return error(res, 404, 'EVENTO_NO_ENCONTRADO', 'Ese evento no existe');
      }
      responder(res, 200, aforoDe(m[1]));
    },
  },

  {
    // Paso 1. Bloquea butacas y devuelve hasta cuándo.
    metodo: 'POST',
    patron: /^\/reservas$/,
    manejar(req, res, _m, cuerpo) {
      // La clave de idempotencia se mira ANTES de validar nada: si el cliente
      // reintenta porque no recibió la respuesta, lo que tiene que recibir es
      // la misma reserva, no una segunda.
      const clave = req.headers['idempotency-key'];
      if (clave && idempotencia.has(clave)) {
        const yaCreada = reservas.get(idempotencia.get(clave));
        return responder(res, 200, yaCreada, { 'idempotent-replay': 'true' });
      }

      const mal = [];
      if (!EVENTOS.has(cuerpo.evento)) {
        mal.push({ campo: 'evento', motivo: 'código de evento desconocido' });
      }
      if (!Number.isInteger(cuerpo.butacas) || cuerpo.butacas < 1 || cuerpo.butacas > 6) {
        mal.push({ campo: 'butacas', motivo: 'debe ser un entero entre 1 y 6' });
      }
      if (typeof cuerpo.asistente !== 'string' || !/^AST-\d{4}$/.test(cuerpo.asistente)) {
        mal.push({ campo: 'asistente', motivo: 'debe tener la forma AST-0000' });
      }
      if (mal.length) {
        return error(res, 400, 'RESERVA_INVALIDA', 'La reserva no supera la validación', {
          campos: mal,
        });
      }

      const aforo = aforoDe(cuerpo.evento);
      if (cuerpo.butacas > aforo.disponible) {
        return error(res, 409, 'AFORO_INSUFICIENTE', 'No quedan butacas suficientes', {
          disponible: aforo.disponible,
        });
      }

      const id = nuevoId();
      const reserva = {
        id,
        estado: 'BLOQUEADA',
        evento: cuerpo.evento,
        butacas: cuerpo.butacas,
        asistente: cuerpo.asistente,
        creadaEn: new Date().toISOString(),
        expiraEn: new Date(Date.now() + SEGUNDOS_DE_BLOQUEO * 1000).toISOString(),
        billete: null,
      };
      reservas.set(id, reserva);
      if (clave) idempotencia.set(clave, id);
      responder(res, 201, reserva, { location: `/reservas/${id}` });
    },
  },

  {
    metodo: 'GET',
    patron: /^\/reservas\/(RES-\d{4})$/,
    manejar(_req, res, m) {
      barrerExpiradas();
      const reserva = reservas.get(m[1]);
      if (!reserva) return error(res, 404, 'RESERVA_NO_ENCONTRADA', 'Esa reserva no existe');
      responder(res, 200, reserva);
    },
  },

  {
    // Paso 2. Consume el aforo de verdad.
    metodo: 'POST',
    patron: /^\/reservas\/(RES-\d{4})\/pago$/,
    manejar(req, res, m) {
      barrerExpiradas();
      const reserva = reservas.get(m[1]);
      if (!reserva) return error(res, 404, 'RESERVA_NO_ENCONTRADA', 'Esa reserva no existe');
      if (reserva.estado === 'EXPIRADA') {
        return error(res, 409, 'RESERVA_EXPIRADA', 'El bloqueo caducó antes del pago');
      }
      if (reserva.estado !== 'BLOQUEADA') {
        return error(res, 409, 'ESTADO_INVALIDO', `No se puede pagar en estado ${reserva.estado}`);
      }

      const estadoAnterior = reserva.estado;
      reserva.estado = 'PAGADA';
      reserva.pagadaEn = new Date().toISOString();

      if (req.headers['x-fallo-en'] === 'pago') {
        // Aquí está el defecto entero, y cabe en un `if`.
        //
        // El estado ya cambió a PAGADA y entonces el cobro revienta. El
        // cliente recibe un 500 y da la operación por no hecha. El aforo, en
        // cambio, está consumido: nadie lo devuelve, porque la reserva ya no
        // es BLOQUEADA y el barrido de caducidad solo mira las bloqueadas.
        //
        // Son butacas que nadie compró y que nadie podrá comprar. No se ven en
        // ninguna respuesta por separado: hay que comparar el aforo de antes
        // del flujo con el de después, y eso solo lo hace quien prueba flujos.
        if (CORREGIDA) {
          reserva.estado = estadoAnterior;
          delete reserva.pagadaEn;
        }
        return error(res, 500, 'ERROR_INTERNO', 'Falló el cobro');
      }

      responder(res, 200, reserva);
    },
  },

  {
    // Paso 3. Emite el billete.
    metodo: 'POST',
    patron: /^\/reservas\/(RES-\d{4})\/emision$/,
    manejar(req, res, m) {
      barrerExpiradas();
      const reserva = reservas.get(m[1]);
      if (!reserva) return error(res, 404, 'RESERVA_NO_ENCONTRADA', 'Esa reserva no existe');
      if (reserva.estado !== 'PAGADA') {
        return error(res, 409, 'ESTADO_INVALIDO', `No se puede emitir en estado ${reserva.estado}`);
      }

      if (req.headers['x-fallo-en'] === 'emision') {
        // Un 500 distinto del anterior, y la diferencia es el repositorio
        // entero: aquí NO se ha tocado el estado. La reserva se queda PAGADA,
        // el aforo sigue consumido —que es lo correcto, está pagada— y el
        // cliente puede reintentar la emisión sin perder nada.
        //
        // Los dos responden 500 con el mismo cuerpo. Una suite que solo mire
        // la respuesta los da por iguales. Son opuestos: uno es recuperable y
        // el otro pierde inventario para siempre.
        return error(res, 500, 'ERROR_INTERNO', 'Falló la emisión del billete');
      }

      reserva.estado = 'EMITIDA';
      reserva.billete = `BIL-${reserva.id.slice(4)}`;
      responder(res, 200, reserva);
    },
  },

  {
    metodo: 'DELETE',
    patron: /^\/reservas\/(RES-\d{4})$/,
    manejar(req, res, m) {
      barrerExpiradas();
      const sesion = sesionDe(req);
      const reserva = reservas.get(m[1]);
      if (!reserva) return error(res, 404, 'RESERVA_NO_ENCONTRADA', 'Esa reserva no existe');
      // 403 y no 401: el auditor está autenticado, lo que le falta es permiso.
      // Devolver 401 lo manda a renovar un token que está bien.
      if (sesion.rol !== 'VENDEDOR') {
        return error(res, 403, 'SIN_PERMISO', 'Hace falta el rol VENDEDOR');
      }
      if (reserva.estado === 'EMITIDA') {
        return error(res, 409, 'ESTADO_INVALIDO', 'Un billete emitido no se anula aquí');
      }
      reserva.estado = 'ANULADA';
      responder(res, 204);
    },
  },
];

// --- Servidor ---------------------------------------------------------------

const servidor = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PUERTO}`);
  let cuerpo = '';
  req.on('data', (t) => {
    cuerpo += t;
  });
  req.on('end', () => {
    let json = {};
    if (cuerpo) {
      try {
        json = JSON.parse(cuerpo);
      } catch {
        return error(res, 400, 'JSON_INVALIDO', 'El cuerpo no es JSON válido');
      }
    }

    for (const ruta of rutas) {
      if (ruta.metodo !== req.method) continue;
      const m = ruta.patron.exec(url.pathname);
      if (!m) continue;
      if (!ruta.publica && !sesionDe(req)) {
        return error(res, 401, 'NO_AUTENTICADO', 'Hace falta un token de sesión');
      }
      return ruta.manejar(req, res, m, json);
    }
    error(res, 404, 'RUTA_NO_ENCONTRADA', 'Esa ruta no existe');
  });
});

servidor.listen(PUERTO, () => {
  const modo = CORREGIDA ? 'CON compensación' : 'sin compensación (pierde aforo)';
  console.log(`API de reservas en http://localhost:${PUERTO} — ${modo}`);
  console.log(`Bloqueo de ${SEGUNDOS_DE_BLOQUEO} s`);
});
