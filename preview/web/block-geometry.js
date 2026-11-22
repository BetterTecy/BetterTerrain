import { BufferAttribute, BufferGeometry } from "three";
import { BLOCK, surfaceMaterial } from "./block-materials.js";

export function contains(window, x, z) {
    return x >= window.x0 && x < window.x0 + window.size && z >= window.z0 && z < window.z0 + window.size;
}

export function column(layer, x, z, fallback) {
    if (!layer || !contains(layer.window, x, z)) return fallback;
    const { x0, z0, stride, size } = layer.window;
    return layer.heights[Math.floor((z - z0) / stride) * (size / stride) + Math.floor((x - x0) / stride)];
}

function materialAt(layer, x, z, height) {
    if (!layer) return surfaceMaterial(height, 0);
    const step = layer.window.stride;
    const slope = Math.max(
        Math.abs(column(layer, x + step, z, height) - height), Math.abs(column(layer, x - step, z, height) - height),
        Math.abs(column(layer, x, z + step, height) - height), Math.abs(column(layer, x, z - step, height) - height),
    ) / step;
    return surfaceMaterial(height, slope);
}

export function blockGeometry(layer, outer, inner, base) {
    const { x0, z0, stride, size } = layer.window;
    const count = size / stride;
    const { heights } = layer;
    const excluded = new Uint8Array(heights.length);
    const drawn = new Uint8Array(heights.length);
    const surfaces = new Uint8Array(heights.length);
    const positions = [];
    const indices = Object.keys(BLOCK).map(() => []);
    const uvs = [];
    const at = (x, z) => z * count + x;
    const xAt = x => x0 + x * stride;
    const zAt = z => z0 + z * stride;
    for (let z = 0; z < count; z++) {
        for (let x = 0; x < count; x++) {
            const index = at(x, z);
            excluded[index] = Boolean(inner && contains(inner.window, xAt(x), zAt(z)));
            if (!excluded[index]) surfaces[index] = materialAt(layer, xAt(x), zAt(z), heights[index]);
        }
    }

    function quad(vertices, material, side = null, reversed = false) {
        const offset = positions.length / 3;
        for (let index = 0; index < vertices.length; index += 3) {
            const x = vertices[index];
            const y = vertices[index + 1];
            const z = vertices[index + 2];
            positions.push(x - x0, y, z - z0);
            uvs.push(side === "x" ? z : x, side ? y : z);
        }
        const faces = indices[material];
        if (reversed) faces.push(offset, offset + 2, offset + 1, offset, offset + 3, offset + 2);
        else faces.push(offset, offset + 1, offset + 2, offset, offset + 2, offset + 3);
    }

    // 同高同材质的方块顶面合并为矩形。
    function matches(x, z, height, material) {
        const index = at(x, z);
        return !excluded[index] && !drawn[index] && heights[index] === height && surfaces[index] === material;
    }

    for (let z = 0; z < count; z++) {
        for (let x = 0; x < count; x++) {
            const height = heights[at(x, z)];
            const material = surfaces[at(x, z)];
            if (!matches(x, z, height, material)) continue;
            let endX = x + 1;
            while (endX < count && matches(endX, z, height, material)) endX++;
            let endZ = z + 1;
            rows: for (; endZ < count; endZ++) {
                for (let column = x; column < endX; column++) {
                    if (!matches(column, endZ, height, material)) break rows;
                }
            }
            for (let row = z; row < endZ; row++) drawn.fill(1, at(x, row), at(endX, row));
            quad([xAt(x), height, zAt(z), xAt(x), height, zAt(endZ),
                xAt(endX), height, zAt(endZ), xAt(endX), height, zAt(z)], material);
        }
    }

    function wall(axis, coordinate, start, end, from, to, material) {
        const low = Math.min(from, to);
        const high = Math.max(from, to);
        function strip(bottom, top, texture) {
            if (bottom >= top) return;
            const vertices = axis === "x"
                ? [coordinate, bottom, start, coordinate, top, start, coordinate, top, end, coordinate, bottom, end]
                : [start, bottom, coordinate, end, bottom, coordinate, end, top, coordinate, start, top, coordinate];
            quad(vertices, texture, axis, from < to);
        }
        if (material === BLOCK.grass) {
            strip(low, high - 4, BLOCK.stone);
            strip(Math.max(low, high - 4), high - 1, BLOCK.dirt);
            strip(Math.max(low, high - 1), high, BLOCK.grassSide);
        } else if (material === BLOCK.snow) {
            strip(low, high - 1, BLOCK.stone);
            strip(Math.max(low, high - 1), high, BLOCK.snowSide);
        } else {
            strip(low, high, BLOCK.stone);
        }
    }

    function wallsAlong(axis, line, pair) {
        const coordinate = axis === "x" ? xAt(line) : zAt(line);
        const origin = axis === "x" ? z0 : x0;
        let start = 0;
        let from = 0;
        let to = 0;
        let material = BLOCK.stone;
        for (let cell = 0; cell <= count; cell++) {
            const next = cell < count ? pair(cell) : null;
            if (next && from === next[0] && to === next[1] && material === next[2]) continue;
            if (from !== to) wall(axis, coordinate, origin + start * stride, origin + cell * stride, from, to, material);
            start = cell;
            [from, to, material] = next ?? [0, 0, BLOCK.stone];
        }
    }

    for (const axis of ["x", "z"]) {
        for (let line = 1; line < count; line++) {
            wallsAlong(axis, line, cell => {
                const before = axis === "x" ? at(line - 1, cell) : at(cell, line - 1);
                const after = axis === "x" ? at(line, cell) : at(cell, line);
                if (excluded[before] || excluded[after] || heights[before] === heights[after]) return null;
                return [heights[before], heights[after], surfaces[heights[before] > heights[after] ? before : after]];
            });
        }

        // 细层独占接缝，按更细的跨度连接粗层方块和细层方块。
        for (const line of [0, count]) {
            const leading = line === 0;
            wallsAlong(axis, line, cell => {
                const edge = leading ? 0 : count - 1;
                const own = axis === "x" ? at(edge, cell) : at(cell, edge);
                const outside = line * stride - (leading ? 1 : 0);
                const x = axis === "x" ? x0 + outside : xAt(cell);
                const z = axis === "x" ? zAt(cell) : z0 + outside;
                const neighbor = column(outer, x, z, base - 8);
                const material = heights[own] >= neighbor ? surfaces[own] : materialAt(outer, x, z, neighbor);
                return leading ? [neighbor, heights[own], material] : [heights[own], neighbor, material];
            });
        }
    }

    const geometry = new BufferGeometry();
    geometry.setAttribute("position", new BufferAttribute(new Float32Array(positions), 3));
    geometry.setAttribute("uv", new BufferAttribute(new Float32Array(uvs), 2));
    const ordered = new Uint32Array(indices.reduce((size, faces) => size + faces.length, 0));
    let groupStart = 0;
    for (const [material, faces] of indices.entries()) {
        if (!faces.length) continue;
        ordered.set(faces, groupStart);
        geometry.addGroup(groupStart, faces.length, material);
        groupStart += faces.length;
    }
    geometry.setIndex(new BufferAttribute(ordered, 1));
    geometry.computeVertexNormals();
    return geometry;
}
