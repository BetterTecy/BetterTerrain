import { Color, SRGBColorSpace } from "three";
import { TerrainView, heightColor, landforms } from "./terrain-view.js";
import { contains } from "./block-geometry.js";

const elements = Object.fromEntries(
    ["seed", "view", "markers", "extent", "sampling", "status", "map", "landmarks", "length",
        "point-name", "elevation", "position", "cloud-gap", "datum", "cloud", "reset", "profile",
        "flight", "view-help", "flight-position"]
        .map(id => [id, document.getElementById(id)]),
);
const levels = ["overview", "section", "detail", "blocks"];
const formatInteger = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 });
const formatHeight = new Intl.NumberFormat("zh-CN", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const heightCache = new Map();
const levelButtons = [...document.querySelectorAll("[data-level]")];
const profileButtons = [...document.querySelectorAll("[data-profile]")];
let manifest;
let renderer;
let world;
let level = "overview";
let selected;
let profile = "ridge";
let request = 0;
let shownWorld;
let mapBackground;
let screenPoints = [];
let cameraTimer;
let cameraSample;

async function read(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`无法读取 ${url}（HTTP ${response.status}）`);
    return response;
}

async function heightsFor(window) {
    if (!heightCache.has(window.file)) {
        const pending = read(`./data/${window.file}`).then(response => response.arrayBuffer()).then(buffer => {
            if (buffer.byteLength !== manifest.gridSize ** 2 * 2) throw new Error("高程网格大小有误");
            const view = new DataView(buffer);
            return Int16Array.from({ length: manifest.gridSize ** 2 }, (_, index) => view.getInt16(index * 2, true));
        });
        heightCache.set(window.file, pending);
        pending.catch(() => heightCache.delete(window.file));
    }
    return heightCache.get(window.file);
}

function point() {
    return world.landmarks.find(entry => entry.id === selected);
}

function name(landmark) {
    return landforms[landmark.kind].name;
}

function chooseLandmark(id) {
    selected = id;
    focus(level === "overview" ? "section" : level);
}

function focus(nextLevel, reset = false) {
    leaveFlight();
    clearTimeout(cameraTimer);
    cameraTimer = undefined;
    level = nextLevel;
    renderer.focus(level === "overview" ? world.overview : point()[level], level, reset);
    return show();
}

function flightUi(active) {
    elements.view.dataset.mode = active ? "flight" : "orbit";
    elements.flight.setAttribute("aria-pressed", active);
    elements.flight.textContent = active ? "返回透视" : "自由飞行";
    elements["flight-position"].hidden = !active;
    elements["view-help"].textContent = active
        ? "点击或拖动环顾 · WASD 飞行 · 空格 / Shift 升降 · Esc 返回"
        : "拖动旋转 · 滚轮靠近 · 右键平移";
}

function leaveFlight() {
    if (!renderer.flight.active) return;
    renderer.stopFlight();
    flightUi(false);
}

async function toggleFlight() {
    if (renderer.flight.active) {
        focus("blocks");
        return;
    }
    elements.flight.disabled = true;
    try {
        if (await focus("blocks")) {
            flightUi(true);
            renderer.startFlight(point());
        }
    } finally {
        elements.flight.disabled = false;
    }
}

function followCamera(target, distance) {
    if (renderer.flight.active) {
        elements["flight-position"].textContent = `X ${Math.round(target.x)} · Y ${Math.round(target.y)} · Z ${Math.round(target.z)}`;
    }
    cameraSample = { x: target.x, z: target.z, distance };
    if (cameraTimer !== undefined) return;
    cameraTimer = setTimeout(() => {
        cameraTimer = undefined;
        const { x, z, distance } = cameraSample;
        let nextLevel = "overview";
        let nextPoint = point();
        if (distance < 7_000) {
            const candidates = world.landmarks.filter(entry => contains(entry.section, x, z));
            if (candidates.length) {
                const gap = entry => (entry.x - x) ** 2 + (entry.z - z) ** 2;
                nextPoint = candidates.reduce((near, entry) => gap(entry) < gap(near) ? entry : near);
                nextLevel = "section";
                if (distance < 2_150 && contains(nextPoint.detail, x, z)) nextLevel = "detail";
                if (distance < 640 && contains(nextPoint.blocks, x, z)) nextLevel = "blocks";
            }
        }
        if (nextLevel === level && nextPoint.id === selected) return;
        level = nextLevel;
        selected = nextPoint.id;
        show();
    }, 100);
}

function updateSelection() {
    const landmark = point();
    const massifs = world.landmarks.length - world.rangeCount;
    elements.length.textContent = `${world.rangeCount} 条山脉${massifs ? ` · ${massifs} 片山地` : ""}`;
    elements["point-name"].textContent = name(landmark);
    elements.elevation.textContent = `Y ${formatHeight.format(landmark.y)}`;
    elements.position.textContent = `X ${formatInteger.format(landmark.x)} · Z ${formatInteger.format(landmark.z)}`;
    const difference = landmark.y - manifest.cloudLevel;
    elements["cloud-gap"].textContent = `${difference >= 0 ? "高出" : "低于"}云层 ${formatHeight.format(Math.abs(difference))} 格`;
    levelButtons.forEach(button => button.setAttribute("aria-pressed", button.dataset.level === level));
    for (const button of elements.landmarks.children) {
        button.setAttribute("aria-pressed", button.dataset.landmark === selected);
    }
}

async function show() {
    const update = ++request;
    const activeWorld = world;
    const activeLevel = level;
    const activePoint = point();
    const windows = levels.slice(0, levels.indexOf(activeLevel) + 1)
        .map(entry => entry === "overview" ? activeWorld.overview : activePoint[entry]);
    const window = windows.at(-1);
    updateSelection();
    drawProfile();
    elements.status.textContent = "正在载入地形…";
    elements.status.hidden = Boolean(shownWorld);
    elements.view.setAttribute("aria-busy", "true");
    try {
        const layers = await Promise.all(windows.map(async window => ({ window, heights: await heightsFor(window) })));
        if (update !== request) return;
        const newWorld = shownWorld !== activeWorld;
        if (newWorld) prepareMap(layers[0].heights);
        drawMap(window);
        const started = performance.now();
        renderer.setTerrain(layers, activeWorld);
        shownWorld = activeWorld;
        const size = formatInteger.format(window.size);
        elements.extent.textContent = `${size} × ${size} 格`;
        elements.sampling.textContent = window.stride === 1
            ? "单方块精度 · 1:1" : `每 ${window.stride} 格采样 · 1:1`;
        elements.view.dataset.ready = activeLevel;
        elements.view.dataset.seed = activeWorld.seed;
        elements.view.dataset.sample = activeWorld.id;
        elements.view.dataset.meshMs = String(Math.round(performance.now() - started));
        elements.status.hidden = true;
        return true;
    } catch (error) {
        if (update === request) {
            elements.status.textContent = error.message;
            elements.status.hidden = false;
        }
    } finally {
        if (update === request) elements.view.setAttribute("aria-busy", "false");
    }
}

function prepareMap(heights) {
    const { width, height } = elements.map;
    const { x0, z0, stride, size } = world.overview;
    const ridgePoints = [...world.ridges, ...world.connections].flat();
    const minX = Math.max(x0, Math.min(...ridgePoints.map(sample => sample.x)));
    const maxX = Math.min(x0 + size, Math.max(...ridgePoints.map(sample => sample.x)));
    const minZ = Math.max(z0, Math.min(...ridgePoints.map(sample => sample.z)));
    const maxZ = Math.min(z0 + size, Math.max(...ridgePoints.map(sample => sample.z)));
    const scale = Math.min((width - 34) / (maxX - minX + 1_700), (height - 34) / (maxZ - minZ + 1_700));
    const left = (minX + maxX - width / scale) / 2;
    const top = (minZ + maxZ - height / scale) / 2;
    const ctx = elements.map.getContext("2d");
    const image = ctx.createImageData(width, height);
    const color = new Color();
    const last = manifest.gridSize - 1;
    const altitude = (x, z) => heights[Math.min(last, Math.max(0, z)) * manifest.gridSize
        + Math.min(last, Math.max(0, x))];
    for (let y = 0; y < height; y++) {
        const row = Math.floor((top + y / scale - z0) / stride);
        for (let x = 0; x < width; x++) {
            const column = Math.floor((left + x / scale - x0) / stride);
            const surface = altitude(column, row);
            const slope = altitude(column + 1, row + 1) - altitude(column - 1, row - 1);
            heightColor(surface, color).getRGB(color, SRGBColorSpace);
            const shade = Math.max(0.52, Math.min(1.1, 0.68 + (surface - manifest.baseHeight) / 900 + slope / 390));
            const index = (y * width + x) * 4;
            image.data[index] = color.r * shade * 255;
            image.data[index + 1] = color.g * shade * 255;
            image.data[index + 2] = color.b * shade * 255;
            image.data[index + 3] = 255;
        }
    }
    const project = position => ({ x: (position.x - left) * scale, y: (position.z - top) * scale });
    mapBackground = { image, project, scale };
    screenPoints = world.landmarks.map(landmark => ({ ...project(landmark), landmark, id: landmark.id }));
}

function drawMap(window) {
    const ctx = elements.map.getContext("2d");
    const { image, project, scale } = mapBackground;
    ctx.putImageData(image, 0, 0);
    const origin = project({ x: window.x0, z: window.z0 });
    ctx.setLineDash([5, 4]);
    ctx.lineWidth = 2;
    ctx.strokeStyle = "#d1ffdcbb";
    ctx.strokeRect(origin.x, origin.y, window.size * scale, window.size * scale);
    ctx.setLineDash([]);
    function ridgePath(ridge, color, width) {
        ctx.beginPath();
        ctx.strokeStyle = color;
        ctx.lineWidth = width;
        ridge.forEach((sample, index) => {
            const position = project(sample);
            if (index === 0) ctx.moveTo(position.x, position.y);
            else ctx.lineTo(position.x, position.y);
        });
        ctx.stroke();
    }
    world.connections.forEach(ridge => ridgePath(ridge, "#e9e0f0aa", 1.15));
    world.landmarks.forEach(landmark => ridgePath(world.ridges[landmark.band], landforms[landmark.kind].color, 1.65));
    ctx.font = "bold 14px system-ui";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    screenPoints.forEach(position => {
        const active = position.id === selected;
        const { landmark } = position;
        const color = landforms[landmark.kind].color;
        ctx.beginPath();
        ctx.arc(position.x, position.y, active ? 13 : 11, 0, Math.PI * 2);
        ctx.fillStyle = active ? color : "#14282a";
        ctx.fill();
        ctx.strokeStyle = color;
        ctx.stroke();
        ctx.fillStyle = active ? "#182b26" : color;
        ctx.fillText(landmark.number || "M", position.x, position.y);
    });
}

function drawProfile() {
    const canvas = elements.profile;
    const { clientWidth: width, clientHeight: height } = canvas;
    if (!world || !width || !height) return;
    const ratio = Math.min(window.devicePixelRatio, 2);
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    const ctx = canvas.getContext("2d");
    ctx.scale(ratio, ratio);
    const landmark = point();
    const points = profile === "ridge" ? world.ridges[landmark.band] : landmark.cross;
    const total = points.at(-1).distance;
    const left = 41;
    const right = width - 12;
    const bottom = height - 23;
    const top = 12;
    const x = distance => left + distance / total * (right - left);
    const y = altitude => bottom - (altitude - 90) / 350 * (bottom - top);
    ctx.font = "12px system-ui";
    ctx.textBaseline = "middle";
    for (const altitude of [120, 240, manifest.cloudLevel]) {
        const cloud = altitude === manifest.cloudLevel;
        ctx.strokeStyle = cloud ? "#93b9cc" : "#344548";
        ctx.setLineDash(cloud ? [5, 5] : []);
        ctx.beginPath();
        ctx.moveTo(left, y(altitude));
        ctx.lineTo(right, y(altitude));
        ctx.stroke();
        ctx.fillStyle = cloud ? "#bdd7e4" : "#a0b4b2";
        ctx.fillText(String(altitude), 3, y(altitude));
    }
    ctx.setLineDash([]);
    ctx.beginPath();
    points.forEach((sample, index) => {
        if (index === 0) ctx.moveTo(x(sample.distance), y(sample.height));
        else ctx.lineTo(x(sample.distance), y(sample.height));
    });
    ctx.lineWidth = 2;
    ctx.strokeStyle = "#baf1ca";
    ctx.stroke();
    ctx.fillStyle = "#a0b4b2";
    ctx.fillText("0", left, height - 10);
    ctx.textAlign = "right";
    ctx.fillText(`${formatInteger.format(total)} 格`, right, height - 10);
}

function selectSample(id) {
    world = manifest.seeds.find(entry => entry.id === id);
    selected = world.landmarks[0].id;
    elements.landmarks.replaceChildren();
    world.landmarks.forEach(landmark => {
        const button = document.createElement("button");
        button.type = "button";
        button.dataset.landmark = landmark.id;
        button.dataset.kind = landmark.kind;
        const marker = document.createElement("span");
        marker.textContent = landmark.number || "M";
        marker.style.color = landforms[landmark.kind].color;
        marker.setAttribute("aria-hidden", "true");
        button.append(marker, name(landmark));
        button.addEventListener("click", () => chooseLandmark(landmark.id));
        elements.landmarks.append(button);
    });
    return focus("overview", true);
}

async function initialize() {
    try {
        manifest = await (await read("./data/terrain.json")).json();
        renderer = new TerrainView(elements.view, elements.markers, manifest.cloudLevel, manifest.baseHeight);
        renderer.onCameraChange = followCamera;
        renderer.onExitFlight = () => focus("blocks");
        elements.flight.addEventListener("click", toggleFlight);
        manifest.seeds.forEach(entry => {
            const { x0, z0, size } = entry.overview;
            const center = entry.id === entry.seed ? "原点"
                : `${formatInteger.format(x0 + size / 2)}, ${formatInteger.format(z0 + size / 2)}`;
            elements.seed.add(new Option(`${entry.seed} · ${center}`, entry.id));
        });
        elements.seed.disabled = false;
        elements.datum.textContent = `海平面 Y ${manifest.seaLevel} · 云层 Y ${manifest.cloudLevel} · 高度上界 Y ${manifest.worldTop}`;
        elements.map.addEventListener("click", event => {
            const bounds = elements.map.getBoundingClientRect();
            const x = (event.clientX - bounds.left) * elements.map.width / bounds.width;
            const y = (event.clientY - bounds.top) * elements.map.height / bounds.height;
            const marker = screenPoints.find(candidate => Math.hypot(candidate.x - x, candidate.y - y) <= 15);
            if (marker) chooseLandmark(marker.id);
        });
        elements.seed.addEventListener("change", () => selectSample(elements.seed.value));
        elements.cloud.addEventListener("change", () => renderer.setCloud(elements.cloud.checked));
        elements.reset.addEventListener("click", () => focus("overview", true));
        levelButtons.forEach(button => button.addEventListener("click", () => {
            focus(button.dataset.level);
        }));
        profileButtons.forEach(button => button.addEventListener("click", () => {
            profile = button.dataset.profile;
            profileButtons.forEach(entry => entry.setAttribute("aria-pressed", entry === button));
            drawProfile();
        }));
        new ResizeObserver(drawProfile).observe(elements.profile);
        if (await selectSample(manifest.seeds[0].id)) elements.flight.disabled = false;
    } catch (error) {
        elements.status.textContent = error.message;
    }
}

initialize();
