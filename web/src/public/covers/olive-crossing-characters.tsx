// Cover users: playable 3D-voxel Olive characters for Olive Crossing.
import type { CrossingDirection } from "./olive-crossing";

export type OliveCharacterId =
  | "classic"
  | "sproutling"
  | "knight"
  | "golden"
  | "ninja"
  | "chef";

export interface OliveCharacter {
  id: OliveCharacterId;
  name: string;
  cost: number;
  tagline: string;
  bodyTop: string;
  bodyFront: string;
  bodySide: string;
  stroke: string;
  crestFill: string;
  crestDark: string;
  footFill: string;
}

export const OLIVE_CHARACTERS: OliveCharacter[] = [
  {
    id: "classic",
    name: "Classic Olive",
    cost: 0,
    tagline: "Plump green olive with a bright pimento crown.",
    bodyTop: "#89ad54",
    bodyFront: "#668b38",
    bodySide: "#4d6d28",
    stroke: "#2d421a",
    crestFill: "#e45242",
    crestDark: "#b33427",
    footFill: "#d67d39",
  },
  {
    id: "sproutling",
    name: "Sproutling",
    cost: 0,
    tagline: "Sunny young olive topped with twin garden leaves.",
    bodyTop: "#a4cb65",
    bodyFront: "#7ba642",
    bodySide: "#5b822d",
    stroke: "#344e1d",
    crestFill: "#69bf4b",
    crestDark: "#459030",
    footFill: "#e29a42",
  },
  {
    id: "knight",
    name: "Kalamata Knight",
    cost: 2,
    tagline: "Deep-plum olive wearing a gleaming silver helm.",
    bodyTop: "#7c5678",
    bodyFront: "#5b3a58",
    bodySide: "#41273f",
    stroke: "#281728",
    crestFill: "#d95b4e",
    crestDark: "#a63d33",
    footFill: "#8996a0",
  },
  {
    id: "golden",
    name: "Golden Harvest",
    cost: 4,
    tagline: "Sun-cured golden olive in a woven straw orchard hat.",
    bodyTop: "#ecc154",
    bodyFront: "#cf9d2d",
    bodySide: "#9f761b",
    stroke: "#573d0b",
    crestFill: "#f4dfb0",
    crestDark: "#cda868",
    footFill: "#c86d31",
  },
  {
    id: "ninja",
    name: "Tapenade Ninja",
    cost: 6,
    tagline: "Stealthy black olive with a crimson orchard headband.",
    bodyTop: "#4f5652",
    bodyFront: "#353b38",
    bodySide: "#222725",
    stroke: "#111513",
    crestFill: "#de4747",
    crestDark: "#a52d2d",
    footFill: "#7d2929",
  },
  {
    id: "chef",
    name: "Chef Pimento",
    cost: 8,
    tagline: "Grove baker wearing a tall puffy white chef toque.",
    bodyTop: "#7da166",
    bodyFront: "#5d7d47",
    bodySide: "#445f32",
    stroke: "#26381b",
    crestFill: "#ffffff",
    crestDark: "#dce4d9",
    footFill: "#df7f3c",
  },
];

export function getOliveCharacter(id: string): OliveCharacter {
  return OLIVE_CHARACTERS.find((item) => item.id === id) ?? OLIVE_CHARACTERS[0];
}

export function VoxelOlive({
  character,
  facing = "up",
}: {
  character: OliveCharacter;
  facing?: CrossingDirection;
}) {
  const eyeShiftX = facing === "left" ? -3 : facing === "right" ? 3 : 0;
  const eyeShiftY = facing === "up" ? -1.5 : facing === "down" ? 2 : 0.5;
  const sideFlip = facing === "left" ? -1 : 1;

  return (
    <g>
      {/* Symmetric Voxel Olive Body + Headgear flipped together so crests, stems, headband tails, and bevels stay aligned */}
      <g transform={`scale(${sideFlip} 1)`}>
        {/* Voxel Feet */}
        <g transform={`translate(${facing === "left" || facing === "right" ? 1.5 : 0} 0)`}>
          <rect x="-9" y="7" width="7" height="6" rx="1.5" fill={character.footFill} stroke={character.stroke} strokeWidth="1.2" />
          <rect x="2" y="7" width="7" height="6" rx="1.5" fill={character.footFill} stroke={character.stroke} strokeWidth="1.2" />
        </g>

        {/* Side Bevel */}
        <polygon
          points="11,-13 15,-17 15,6 11,10"
          fill={character.bodySide}
          stroke={character.stroke}
          strokeWidth="1.4"
          strokeLinejoin="round"
        />
        {/* Centered Front Face [-11..+11] */}
        <rect
          x="-11"
          y="-13"
          width="22"
          height="23"
          rx="4"
          fill={character.bodyFront}
          stroke={character.stroke}
          strokeWidth="1.5"
        />
        {/* Sunlit Top Face */}
        <polygon
          points="-11,-13 -7,-18 15,-18 11,-13"
          fill={character.bodyTop}
          stroke={character.stroke}
          strokeWidth="1.4"
          strokeLinejoin="round"
        />
        {/* Voxel Cheek Highlight */}
        <rect x="-8" y="-9" width="14" height="8" rx="2" fill={character.bodyTop} opacity="0.55" />

        {/* Character-Specific Crest / Hat */}
        {character.id === "classic" && (
          <g>
            <rect x="-5" y="-23" width="10" height="7" rx="1.5" fill={character.crestFill} stroke={character.stroke} strokeWidth="1.3" />
            <rect x="-3" y="-22" width="5" height="3" fill="#ff7d6b" />
            <path d="M1-23Q4-29 10-28" fill="none" stroke="#5c4028" strokeWidth="2.5" strokeLinecap="round" />
          </g>
        )}
        {character.id === "sproutling" && (
          <g>
            <rect x="-1.5" y="-24" width="3" height="7" fill="#527829" />
            <polygon points="-1,-23 -12,-28 -9,-21" fill={character.crestFill} stroke={character.stroke} strokeWidth="1.2" />
            <polygon points="1,-23 12,-27 10,-20" fill="#82d463" stroke={character.stroke} strokeWidth="1.2" />
          </g>
        )}
        {character.id === "knight" && (
          <g>
            <rect x="-12" y="-20" width="24" height="9" rx="2" fill="#c7d1d9" stroke="#36424d" strokeWidth="1.4" />
            <rect x="-8" y="-15" width="16" height="3" fill="#232c33" />
            <rect x="-3" y="-28" width="7" height="8" rx="1.5" fill={character.crestFill} stroke={character.stroke} strokeWidth="1.2" />
          </g>
        )}
        {character.id === "golden" && (
          <g>
            <ellipse cx="0" cy="-16" rx="17" ry="4.5" fill={character.crestDark} stroke={character.stroke} strokeWidth="1.3" />
            <rect x="-9" y="-24" width="18" height="8" rx="2" fill={character.crestFill} stroke={character.stroke} strokeWidth="1.3" />
            <rect x="-9" y="-19" width="18" height="2.5" fill="#b44335" />
          </g>
        )}
        {character.id === "ninja" && (
          <g>
            <rect x="-11.5" y="-12" width="23" height="5" rx="1" fill={character.crestFill} stroke="#631b1b" strokeWidth="1.2" />
            {/* Headband tails flow backward behind direction of travel */}
            <polygon points="-11.5,-10 -20,-13 -18,-8" fill={character.crestDark} />
            <polygon points="-11.5,-8 -19,-5 -17,-2" fill={character.crestFill} />
          </g>
        )}
        {character.id === "chef" && (
          <g>
            <rect x="-9" y="-22" width="18" height="7" rx="1.5" fill={character.crestDark} stroke="#8a968b" strokeWidth="1.2" />
            <rect x="-12" y="-30" width="24" height="10" rx="5" fill={character.crestFill} stroke="#8a968b" strokeWidth="1.3" />
          </g>
        )}
      </g>

      {/* Expressive Crossy Eyes aligned cleanly inside centered [-11..+11] body */}
      {facing !== "up" ? (
        <g transform={`translate(${eyeShiftX} ${eyeShiftY})`}>
          <rect x="-6.5" y="-8" width="5" height="6" rx="1.2" fill="#ffffff" />
          <rect x="1.5" y="-8" width="5" height="6" rx="1.2" fill="#ffffff" />
          <rect x={facing === "left" ? -6 : -4} y="-6" width="2.8" height="3.2" fill="#182016" />
          <rect x={facing === "left" ? 2 : 4} y="-6" width="2.8" height="3.2" fill="#182016" />
        </g>
      ) : (
        <g transform="translate(0 -5)">
          <rect x="-10" y="-4" width="3.5" height="4.5" rx="1" fill="#ffffff" />
          <rect x="6.5" y="-4" width="3.5" height="4.5" rx="1" fill="#ffffff" />
          <rect x="-9.5" y="-3" width="2" height="2.5" fill="#182016" />
          <rect x="7.5" y="-3" width="2" height="2.5" fill="#182016" />
        </g>
      )}
    </g>
  );
}
