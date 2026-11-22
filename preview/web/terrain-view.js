import * as THREE from "three";
import { OrbitControls } from "./vendor/examples/jsm/controls/OrbitControls.js";
import { blockGeometry, column } from "./block-geometry.js";
import { createBlockMaterials } from "./block-materials.js";
import { FlightControls } from "./flight-controls.js";

const colors = [
    [118, new THREE.Color("#5e9136")],
    [255, new THREE.Color("#709346")],
    [290, new THREE.Color("#7e7e7a")],
    [365, new THREE.Color("#868683")],
    [383, new THREE.Color("#e5eff7")],
];

export function heightColor(height, result) {
    for (let index = 1; index < colors.length; index++) {
        if (height <= colors[index][0]) {
            const [low, lowColor] = colors[index - 1];
            const [high, highColor] = colors[index];
            return result.copy(lowColor).lerp(highColor, Math.max(0, (height - low) / (high - low)));
        }
    }
    return result.copy(colors.at(-1)[1]);
}

export class TerrainView {
    constructor(element, markerLayer, cloudLevel, baseHeight) {
        this.element = element;
        this.markerLayer = markerLayer;
        this.baseHeight = baseHeight;
        this.renderer = new THREE.WebGLRenderer({ antialias: true });
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this.renderer.domElement.setAttribute("aria-label", "山脉三维模型");
        this.renderer.domElement.setAttribute("role", "img");
        this.renderer.domElement.tabIndex = 0;
        element.prepend(this.renderer.domElement);

        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color("#92bdeb");
        this.flightFog = new THREE.Fog("#b6d4f2", 180, 7_000);
        this.camera = new THREE.PerspectiveCamera(42, 1, 2, 80_000);
        this.controls = new OrbitControls(this.camera, this.renderer.domElement);
        this.controls.zoomToCursor = true;
        this.controls.screenSpacePanning = false;
        this.controls.minDistance = 48;
        this.controls.maxDistance = 38_000;
        this.controls.maxPolarAngle = Math.PI / 2.02;
        this.controls.minPolarAngle = 0.035;
        this.controls.listenToKeyEvents(this.renderer.domElement);
        this.controls.addEventListener("change", () => {
            this.draw();
            if (!this.focusing) this.onCameraChange?.(this.controls.target,
                this.camera.position.distanceTo(this.controls.target));
        });
        this.scene.add(new THREE.HemisphereLight("#e9f4ff", "#a1a097", 1.42));
        const sun = new THREE.DirectionalLight("#fff4df", 1.12);
        sun.position.set(-1, 0.72, -0.85);
        this.scene.add(sun);
        this.material = createBlockMaterials();
        this.flight = new FlightControls(this.camera, this.renderer.domElement,
            position => this.clampFlight(position), () => this.moveFlight(), () => this.onExitFlight?.());
        this.cloud = new THREE.Mesh(new THREE.PlaneGeometry(1, 1),
            new THREE.MeshBasicMaterial({
                color: "#cfe6ee", transparent: true, opacity: 0.34, depthWrite: false, side: THREE.DoubleSide,
            }));
        this.cloud.rotation.x = -Math.PI / 2;
        this.cloud.position.y = cloudLevel;
        this.cloud.visible = false;
        this.scene.add(this.cloud);

        this.meshes = [];
        this.markers = [];
        new ResizeObserver(() => this.resize()).observe(element);
        this.resize();
    }

    resize() {
        const { clientWidth, clientHeight } = this.element;
        if (!clientWidth || !clientHeight) return;
        this.camera.aspect = clientWidth / clientHeight;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(clientWidth, clientHeight, false);
        this.draw();
    }

    setTerrain(layers, world) {
        const oldMeshes = new Map(this.meshes.map(entry => [entry.key, entry.mesh]));
        this.meshes = layers.map((layer, index) => {
            const outer = layers[index - 1];
            const inner = layers[index + 1];
            const key = `${outer?.window.file}|${layer.window.file}|${inner?.window.file}`;
            let mesh = oldMeshes.get(key);
            if (mesh) {
                oldMeshes.delete(key);
            } else {
                const geometry = blockGeometry(layer, outer, inner, this.baseHeight);
                mesh = new THREE.Mesh(geometry, this.material);
                mesh.position.set(layer.window.x0, 0, layer.window.z0);
                this.scene.add(mesh);
            }
            return { key, mesh };
        });
        for (const mesh of oldMeshes.values()) {
            this.scene.remove(mesh);
            mesh.geometry.dispose();
        }
        this.layers = layers;
        if (this.flight.active) this.clampFlight(this.camera.position);
        if (this.world !== world) {
            this.world = world;
            const { x0, z0, size } = world.overview;
            this.cloud.position.x = x0 + size / 2;
            this.cloud.position.z = z0 + size / 2;
            this.cloud.scale.set(size, size, 1);
            this.markerLayer.replaceChildren();
            this.markers = world.landmarks.map((point, index) => {
                const label = document.createElement("span");
                label.className = "peak-marker";
                label.append(String(index + 1));
                const altitude = document.createElement("span");
                altitude.className = "peak-altitude";
                altitude.textContent = ` · Y ${point.y.toFixed(1)}`;
                label.append(altitude);
                this.markerLayer.append(label);
                return { label, position: new THREE.Vector3(point.x, point.y, point.z) };
            });
        }
        this.markerLayer.dataset.overview = layers.length === 1;
        this.draw();
    }

    focus(window, level, reset = false) {
        const { x0, z0, size, min, max } = window;
        const target = this.controls.target;
        const direction = reset
            ? new THREE.Vector3(0.22, 1.18, -0.77).normalize()
            : this.camera.position.clone().sub(target).normalize();
        target.set(x0 + size / 2, min + (max - min) * 0.22, z0 + size / 2);
        const overviewDistance = 19_600 * Math.max(1, 1.15 / this.camera.aspect);
        const distance = { overview: overviewDistance, section: 4_100, detail: 1_280, blocks: 350 }[level];
        this.camera.position.copy(direction).multiplyScalar(distance).add(target);
        this.focusing = true;
        this.controls.update();
        this.focusing = false;
        this.draw();
    }

    heightAt(x, z) {
        for (let index = this.layers.length - 1; index >= 0; index--) {
            const height = column(this.layers[index], x, z, undefined);
            if (height !== undefined) return height;
        }
        return this.baseHeight;
    }

    clampFlight(position) {
        const { x0, z0, size } = this.world.overview;
        position.x = Math.min(x0 + size - 1, Math.max(x0 + 1, position.x));
        position.z = Math.min(z0 + size - 1, Math.max(z0 + 1, position.z));
        position.y = Math.min(3_500, Math.max(this.heightAt(position.x, position.z) + 1.7, position.y));
    }

    moveFlight() {
        const position = this.camera.position;
        this.draw();
        this.onCameraChange?.(position, Math.max(48, (position.y - this.heightAt(position.x, position.z)) * 4));
    }

    startFlight(landmark) {
        this.orbitPosition = this.camera.position.clone();
        this.orbitTarget = this.controls.target.clone();
        this.controls.enabled = false;
        const { x0, z0, size } = landmark.blocks;
        const centerX = x0 + size / 2;
        const centerZ = z0 + size / 2;
        const dx = centerX - landmark.x;
        const dz = centerZ - landmark.z;
        const distance = Math.max(1, Math.hypot(dx, dz));
        const x = centerX - dz / distance * 51;
        const z = centerZ + dx / distance * 51;
        this.camera.position.set(x, Math.max(this.heightAt(x, z) + 12, landmark.y + 28), z);
        this.camera.rotation.order = "YXZ";
        this.camera.lookAt(landmark.x - dx / distance * 24, landmark.y - 7, landmark.z - dz / distance * 24);
        this.camera.fov = 62;
        this.camera.near = 0.15;
        this.camera.far = 20_000;
        this.camera.updateProjectionMatrix();
        this.scene.fog = this.flightFog;
        this.flight.setActive(true);
        this.moveFlight();
    }

    stopFlight() {
        if (!this.flight.active) return;
        this.flight.setActive(false);
        this.camera.position.copy(this.orbitPosition);
        this.controls.target.copy(this.orbitTarget);
        this.camera.fov = 42;
        this.camera.near = 2;
        this.camera.far = 80_000;
        this.camera.updateProjectionMatrix();
        this.scene.fog = null;
        this.controls.enabled = true;
        this.focusing = true;
        this.controls.update();
        this.focusing = false;
        this.draw();
    }

    setCloud(visible) {
        this.cloud.visible = visible;
        this.draw();
    }

    draw() {
        if (!this.meshes.length) return;
        this.renderer.render(this.scene, this.camera);
        const { clientWidth, clientHeight } = this.element;
        const projected = new THREE.Vector3();
        for (const { label, position } of this.markers) {
            projected.copy(position).project(this.camera);
            label.hidden = this.flight.active || projected.z < -1 || projected.z > 1
                || Math.abs(projected.x) > 0.96 || Math.abs(projected.y) > 0.91;
            label.style.left = `${(projected.x * 0.5 + 0.5) * clientWidth}px`;
            label.style.top = `${(0.5 - projected.y * 0.5) * clientHeight - 11}px`;
        }
    }
}
