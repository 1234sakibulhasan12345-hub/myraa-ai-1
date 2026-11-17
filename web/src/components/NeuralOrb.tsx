import { useEffect, useRef } from "react";

export type OrbMood = "idle" | "listening" | "thinking" | "speaking" | "error";

interface NeuralOrbProps {
  size?: number;
  mood?: OrbMood;
  label?: string;
}

export function NeuralOrb({ size = 128, mood = "idle", label = "MYRAA Neural Core" }: NeuralOrbProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const dpr = typeof window !== "undefined" ? Math.min(2, window.devicePixelRatio || 1) : 1;
    canvas.width = size * dpr;
    canvas.height = size * dpr;
    ctx.scale(dpr, dpr);

    const reducedMotion =
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

    let rafId = 0;
    let phase = 0;

    const palette: Record<OrbMood, [string, string, string]> = {
      idle: ["#38BDF8", "#A855F7", "#2DD4BF"],
      listening: ["#2DD4BF", "#38BDF8", "#A855F7"],
      thinking: ["#A855F7", "#38BDF8", "#FBBF24"],
      speaking: ["#38BDF8", "#2DD4BF", "#C084FC"],
      error: ["#FB7185", "#A855F7", "#FBBF24"]
    };

    const draw = () => {
      const [c1, c2, c3] = palette[mood];
      ctx.clearRect(0, 0, size, size);

      const cx = size / 2;
      const cy = size / 2;
      const pulse = mood === "listening" || mood === "speaking" ? Math.sin(phase * 2.4) * 3 : Math.sin(phase) * 1.6;
      const coreRadius = size * 0.27 + pulse;

      // Outer radial aura
      const aura = ctx.createRadialGradient(cx, cy, coreRadius * 0.2, cx, cy, size * 0.48);
      aura.addColorStop(0, `${c1}66`);
      aura.addColorStop(0.55, `${c2}33`);
      aura.addColorStop(1, "transparent");
      ctx.fillStyle = aura;
      ctx.beginPath();
      ctx.arc(cx, cy, size * 0.48, 0, Math.PI * 2);
      ctx.fill();

      // Core 3D sphere shading
      const sphere = ctx.createRadialGradient(
        cx - coreRadius * 0.3,
        cy - coreRadius * 0.3,
        coreRadius * 0.1,
        cx,
        cy,
        coreRadius
      );
      sphere.addColorStop(0, "#FFFFFF");
      sphere.addColorStop(0.28, c1);
      sphere.addColorStop(0.75, c2);
      sphere.addColorStop(1, "#0B102B");

      ctx.fillStyle = sphere;
      ctx.beginPath();
      ctx.arc(cx, cy, coreRadius, 0, Math.PI * 2);
      ctx.fill();

      // Orbiting particles
      const count = 6;
      for (let i = 0; i < count; i++) {
        const angle = phase * (mood === "thinking" ? 1.8 : 0.7) + (i * Math.PI * 2) / count;
        const rx = size * 0.38;
        const ry = size * 0.16;
        const tilt = i % 2 === 0 ? 0.42 : -0.42;
        const px = cx + Math.cos(angle) * rx * Math.cos(tilt) - Math.sin(angle) * ry * Math.sin(tilt);
        const py = cy + Math.cos(angle) * rx * Math.sin(tilt) + Math.sin(angle) * ry * Math.cos(tilt);

        ctx.fillStyle = i % 2 === 0 ? c3 : c1;
        ctx.beginPath();
        ctx.arc(px, py, size * 0.022, 0, Math.PI * 2);
        ctx.fill();
      }

      if (!reducedMotion) {
        phase += mood === "thinking" ? 0.045 : 0.022;
        rafId = requestAnimationFrame(draw);
      }
    };

    draw();
    return () => {
      if (rafId) cancelAnimationFrame(rafId);
    };
  }, [size, mood]);

  return (
    <div
      className={`orb-wrapper ${mood}`}
      style={{ width: size, height: size }}
      role="img"
      aria-label={label}
    >
      <canvas ref={canvasRef} style={{ width: size, height: size, display: "block" }} />
      <span className="orb-ring orb-ring-1" aria-hidden="true" />
      <span className="orb-ring orb-ring-2" aria-hidden="true" />
    </div>
  );
}
