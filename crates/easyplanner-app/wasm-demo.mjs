/**
 * Node demo for the BoltFFI wasm build under dist/wasm/pkg/.
 * Run from this directory: npm install && npm run demo:wasm
 */
import { initialized, distance } from "./dist/wasm/pkg/node.js";

await initialized;

const p1 = { x: 0, y: 0 };
const p2 = { x: 3, y: 4 };

console.log(distance(p1, p2));
