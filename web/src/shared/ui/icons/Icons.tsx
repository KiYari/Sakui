import type { ReactNode } from 'react'

interface IconProps {
    size?: number
}

/** Stroke icons on a 24px grid; colour comes from `currentColor`, so themes need no variants. */
function Svg({ size = 20, children }: IconProps & { children: ReactNode }) {
    return (
        <svg
            width={size}
            height={size}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            focusable="false"
        >
            {children}
        </svg>
    )
}

export const SendIcon = (p: IconProps) => (
    <Svg {...p}>
        <path d="M22 2 11 13" />
        <path d="M22 2 15 22l-4-9-9-4 20-7Z" />
    </Svg>
)

export const PaperclipIcon = (p: IconProps) => (
    <Svg {...p}>
        <path d="m21 11-9.2 9.2a5.5 5.5 0 0 1-7.8-7.8l9.2-9.2a3.7 3.7 0 0 1 5.2 5.2l-9.2 9.2a1.8 1.8 0 0 1-2.6-2.6l8.5-8.5" />
    </Svg>
)

export const SunIcon = (p: IconProps) => (
    <Svg {...p}>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </Svg>
)

export const MoonIcon = (p: IconProps) => (
    <Svg {...p}>
        <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </Svg>
)

export const MonitorIcon = (p: IconProps) => (
    <Svg {...p}>
        <rect x="2" y="4" width="20" height="13" rx="2" />
        <path d="M8 21h8M12 17v4" />
    </Svg>
)

export const CopyIcon = (p: IconProps) => (
    <Svg {...p}>
        <rect x="9" y="9" width="12" height="12" rx="2" />
        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
    </Svg>
)

export const CheckIcon = (p: IconProps) => (
    <Svg {...p}>
        <path d="m5 12 5 5L20 7" />
    </Svg>
)

export const LogOutIcon = (p: IconProps) => (
    <Svg {...p}>
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
        <path d="m16 17 5-5-5-5M21 12H9" />
    </Svg>
)

export const TrashIcon = (p: IconProps) => (
    <Svg {...p}>
        <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6" />
    </Svg>
)

export const LockIcon = (p: IconProps) => (
    <Svg {...p}>
        <rect x="4" y="11" width="16" height="10" rx="2" />
        <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </Svg>
)
