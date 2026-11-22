import { DataTexture, MeshLambertMaterial, NearestFilter, NearestMipmapLinearFilter, RepeatWrapping, SRGBColorSpace } from "three";

export const BLOCK = Object.freeze({ grass: 0, grassSide: 1, dirt: 2, stone: 3, snow: 4, snowSide: 5 });

const palette = [
    [94, 145, 54], [94, 145, 54], [125, 88, 57],
    [126, 126, 122], [229, 239, 247], [229, 239, 247],
];

export function surfaceMaterial(height, slope) {
    if (height >= 383 || (height >= 366 && slope < 0.8)) return BLOCK.snow;
    if (height >= 283 || (height >= 170 && slope > 0.7)) return BLOCK.stone;
    return BLOCK.grass;
}

function pixelNoise(x, y, seed) {
    const mixed = Math.imul(x + seed, 0x27d4eb2d) ^ Math.imul(y + seed * 3, 0x165667b1);
    return (Math.imul(mixed ^ (mixed >>> 15), 0x85ebca6b) >>> 24) / 255;
}

function texture(material) {
    const size = 16;
    const pixels = new Uint8Array(size * size * 4);
    for (let y = 0; y < size; y++) {
        for (let x = 0; x < size; x++) {
            let ground = material;
            if (material === BLOCK.grassSide) ground = y >= 11 + Math.floor(pixelNoise(x, 0, 9) * 4) ? BLOCK.grass : BLOCK.dirt;
            if (material === BLOCK.snowSide) ground = y >= 12 + Math.floor(pixelNoise(x, 0, 17) * 3) ? BLOCK.snow : BLOCK.stone;
            const patch = pixelNoise(x >> 1, y >> 1, ground + 3) - 0.5;
            const grain = pixelNoise(x, y, ground + 23) - 0.5;
            const shade = patch * (ground === BLOCK.snow ? 17 : 34) + grain * 17;
            const index = (y * size + x) * 4;
            for (let channel = 0; channel < 3; channel++) {
                pixels[index + channel] = Math.max(0, Math.min(255, palette[ground][channel] + shade));
            }
            pixels[index + 3] = 255;
        }
    }
    const map = new DataTexture(pixels, size, size);
    map.wrapS = map.wrapT = RepeatWrapping;
    map.magFilter = NearestFilter;
    map.minFilter = NearestMipmapLinearFilter;
    map.generateMipmaps = true;
    map.colorSpace = SRGBColorSpace;
    map.needsUpdate = true;
    return map;
}

export function createBlockMaterials() {
    return palette.map((_, material) => new MeshLambertMaterial({ map: texture(material) }));
}
