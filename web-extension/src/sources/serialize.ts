/**
 * Clone-safe serialisation for `window.postMessage` payloads.
 *
 * Plugin code (and any HTML it parses) can hand back values the structured clone algorithm rejects:
 * DOM nodes, `Document`/`Element` handles, functions, promises, weak collections and circular object
 * graphs. A single such value makes the browser throw
 * `Failed to execute 'postMessage' on 'Window': #<Object> could not be cloned` and the whole reply —
 * metadata, listing, episode list — is lost.
 *
 * {@link sanitizeCloneable} rebuilds the value as plain JSON-compatible data: DOM nodes and
 * functions are dropped, class instances are reduced to their own enumerable fields (`toJSON()` wins
 * when present), and cycles are cut. The result can cross `postMessage`, `chrome.storage.local`, or
 * `JSON.stringify` without surprises.
 *
 * Binary payloads (`ArrayBuffer`, typed arrays, `DataView`) and `Blob`/`File` are clone-compatible
 * and are therefore passed through untouched instead of being expanded into byte arrays.
 */

/** Guard against pathological nesting (a plain-object cycle is caught separately). */
const MAX_DEPTH = 100;

/**
 * Returns a structured-clone-safe copy of `value`.
 *
 * @param value Anything a plugin, a DOM parser or storage may have produced.
 * @returns Plain objects/arrays of primitives; `undefined` when the value itself is not cloneable.
 */
export function sanitizeCloneable<T = unknown>(value: unknown): T {
  return clean(value, new WeakSet<object>(), 0) as T;
}

function clean(value: unknown, ancestors: WeakSet<object>, depth: number): unknown {
  if (value === null || value === undefined) return value;

  const type = typeof value;
  if (type === "string" || type === "boolean") return value;
  if (type === "number") {
    // `NaN`/`Infinity` survive structured clone but not JSON; a JSON-safe `null` is predictable.
    return Number.isFinite(value as number) ? value : null;
  }
  if (type === "bigint") return String(value);
  // Functions and symbols cannot be cloned and carry no data for the receiving side.
  if (type === "function" || type === "symbol") return undefined;

  const object = value as object;

  // Cycles: drop the back-reference, keep the rest of the graph.
  if (ancestors.has(object)) return undefined;
  if (depth > MAX_DEPTH) return null;

  if (isDomNode(object)) return undefined;
  // Binary and blob payloads are cloneable as-is; expanding them would be huge and lossy.
  if (isBinary(object) || isBlob(object)) return value;

  const thenable = value as { then?: unknown };
  if (typeof thenable.then === "function") return undefined;

  ancestors.add(object);
  try {
    if (value instanceof Map) {
      const out: Record<string, unknown> = {};
      for (const [key, entry] of value) {
        // JSON objects only have string keys; `String()` keeps numbers and objects identifiable.
        out[typeof key === "string" ? key : String(key)] = clean(entry, ancestors, depth + 1);
      }
      return out;
    }
    if (value instanceof Set) {
      return Array.from(value, (entry) => {
        const cleaned = clean(entry, ancestors, depth + 1);
        return cleaned === undefined ? null : cleaned;
      });
    }
    if (value instanceof WeakMap || value instanceof WeakSet) return undefined;
    if (value instanceof RegExp) return String(value);
    if (value instanceof Error) return { name: value.name, message: value.message };

    const withToJson = value as { toJSON?: unknown };
    if (typeof withToJson.toJSON === "function") {
      // `Date`, and anything that opted into JSON serialisation, decides its own shape.
      return clean((withToJson.toJSON as () => unknown).call(value), ancestors, depth + 1);
    }

    if (Array.isArray(value)) {
      return value.map((entry) => {
        const cleaned = clean(entry, ancestors, depth + 1);
        return cleaned === undefined ? null : cleaned;
      });
    }

    const out: Record<string, unknown> = {};
    for (const key of Object.keys(object)) {
      let entry: unknown;
      try {
        entry = (object as Record<string, unknown>)[key];
      } catch {
        // A hostile/throwing getter must not take the whole message down.
        continue;
      }
      const cleaned = clean(entry, ancestors, depth + 1);
      if (cleaned === undefined) continue;
      out[key] = cleaned;
    }
    return out;
  } finally {
    ancestors.delete(object);
  }
}

/** A DOM node (or anything that quacks like one, e.g. a linkedom node in tests). */
function isDomNode(value: object): boolean {
  if (typeof Node !== "undefined" && value instanceof Node) return true;
  const candidate = value as { nodeType?: unknown; nodeName?: unknown };
  return typeof candidate.nodeType === "number" && typeof candidate.nodeName === "string";
}

function isBinary(value: object): boolean {
  if (typeof ArrayBuffer === "undefined") return false;
  return value instanceof ArrayBuffer || ArrayBuffer.isView(value);
}

function isBlob(value: object): boolean {
  return typeof Blob !== "undefined" && value instanceof Blob;
}
