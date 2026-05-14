/**
 * Node demo for the BoltFFI wasm build under ../dist/wasm/pkg/.
 * Install @boltffi/runtime from the crate root (parent): cd .. && npm install
 * Then: npm run demo:wasm (here or from parent)
 */
import { initialized, distance } from "../dist/wasm/pkg/node.js";

await initialized;

const p1 = { x: 0, y: 0 };
const p2 = { x: 3, y: 4 };

console.log(distance(p1, p2));
