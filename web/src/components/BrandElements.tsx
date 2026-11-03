// All app roles: draws Olive Branch logos and page decoration.
import type { ReactNode } from "react";
import { ArrowRight, ArrowUpRight, Leaf } from "lucide-react";

// Draws the olive branch used beside the website name.
export function Branch({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 320 180" fill="none" aria-hidden="true">
      <path d="M20 154C86 134 139 95 280 33" stroke="currentColor" strokeWidth="2.5" />
      <path
        d="M71 131C42 130 38 99 39 89C64 96 79 111 71 131ZM105 114C78 105 82 75 84 64C107 77 119 96 105 114ZM143 92C117 81 124 50 131 40C148 56 157 75 143 92ZM181 72C161 57 171 30 180 23C195 39 194 57 181 72ZM218 54C209 34 229 12 238 10C243 32 233 46 218 54ZM73 131C82 151 110 153 127 143C112 129 90 124 73 131ZM117 107C129 130 157 128 172 117C153 103 133 100 117 107ZM158 87C172 106 198 103 211 90C191 79 172 80 158 87ZM200 64C217 79 243 73 254 59C234 49 214 53 200 64Z"
        fill="currentColor"
        fillOpacity=".7"
      />
      <ellipse cx="149" cy="114" rx="9" ry="13" transform="rotate(-28 149 114)" fill="currentColor" />
      <ellipse cx="105" cy="85" rx="8" ry="11" transform="rotate(20 105 85)" fill="currentColor" />
    </svg>
  );
}

// Combines the branch mark, site name, and short brand line.
export function Logo({ small = false }: { small?: boolean }) {
  return (
    <a href="/" className={`brand ${small ? "small" : ""}`} aria-label="Olive Branch home">
      <Leaf aria-hidden="true" strokeWidth={1.2} />
      <span>olive<span className="brand-second">branch</span><i>®</i></span>
    </a>
  );
}

// Draws the small arrow used in branded links and buttons.
export function Forward({ children }: { children: ReactNode }) {
  return <>{children}<ArrowRight size={18} aria-hidden="true" /></>;
}

// Marks links that open another website or browser tab.
export function External({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a className="text-link" href={href} target="_blank" rel="noopener noreferrer">
      {children}<ArrowUpRight size={16} aria-hidden="true" />
    </a>
  );
}
