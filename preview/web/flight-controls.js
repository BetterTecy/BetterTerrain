const MOVEMENT = new Set(["KeyW", "KeyA", "KeyS", "KeyD", "Space", "ShiftLeft", "ShiftRight"]);

export class FlightControls {
    constructor(camera, canvas, constrain, changed, leave) {
        this.camera = camera;
        this.canvas = canvas;
        this.constrain = constrain;
        this.changed = changed;
        this.keys = new Set();

        canvas.addEventListener("pointerdown", event => {
            if (!this.active || event.button !== 0) return;
            canvas.focus();
            this.pointer = event.pointerId;
            this.pointerX = event.clientX;
            this.pointerY = event.clientY;
            canvas.setPointerCapture(event.pointerId);
        });
        canvas.addEventListener("lostpointercapture", () => { this.pointer = undefined; });
        canvas.addEventListener("click", () => {
            if (this.active && document.pointerLockElement !== canvas) {
                const lock = canvas.requestPointerLock?.();
                lock?.catch(() => {});
            }
        });
        document.addEventListener("pointermove", event => {
            const locked = document.pointerLockElement === canvas;
            if (!this.active || (!locked && this.pointer !== event.pointerId)) return;
            const dx = locked ? event.movementX : event.clientX - this.pointerX;
            const dy = locked ? event.movementY : event.clientY - this.pointerY;
            this.pointerX = event.clientX;
            this.pointerY = event.clientY;
            const yaw = camera.rotation.y - dx * 0.0024;
            const pitch = Math.max(-1.54, Math.min(1.54, camera.rotation.x - dy * 0.0024));
            camera.rotation.set(pitch, yaw, 0, "YXZ");
            this.changed();
        });
        window.addEventListener("keydown", event => {
            if (!this.active) return;
            if (event.code === "Escape") {
                leave();
                return;
            }
            if (!MOVEMENT.has(event.code) || (document.activeElement !== canvas && document.pointerLockElement !== canvas)) return;
            event.preventDefault();
            this.keys.add(event.code);
            if (!this.animation) {
                this.previous = performance.now();
                this.animation = requestAnimationFrame(time => this.move(time));
            }
        });
        window.addEventListener("keyup", event => this.keys.delete(event.code));
        window.addEventListener("blur", () => this.keys.clear());
        document.addEventListener("pointerlockchange", () => this.keys.clear());
    }

    setActive(active) {
        this.active = active;
        if (active) {
            this.canvas.focus({ preventScroll: true });
        } else {
            this.keys.clear();
            this.pointer = undefined;
            if (document.pointerLockElement === this.canvas) document.exitPointerLock();
        }
    }

    move(time) {
        this.animation = undefined;
        if (!this.active || !this.keys.size) return;
        const forward = Number(this.keys.has("KeyW")) - Number(this.keys.has("KeyS"));
        const sideways = Number(this.keys.has("KeyD")) - Number(this.keys.has("KeyA"));
        const rise = Number(this.keys.has("Space")) - Number(this.keys.has("ShiftLeft") || this.keys.has("ShiftRight"));
        const step = Math.min((time - this.previous) / 1_000, 0.05) * 46
            / Math.max(1, Math.hypot(forward, sideways, rise));
        const yaw = this.camera.rotation.y;
        const position = this.camera.position;
        position.x += (sideways * Math.cos(yaw) - forward * Math.sin(yaw)) * step;
        position.y += rise * step;
        position.z -= (sideways * Math.sin(yaw) + forward * Math.cos(yaw)) * step;
        this.constrain(position);
        this.changed();
        this.previous = time;
        if (this.active && this.keys.size) this.animation = requestAnimationFrame(next => this.move(next));
    }
}
