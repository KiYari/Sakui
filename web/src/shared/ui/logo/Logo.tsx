import { LockIcon } from '../icons'
import './Logo.css'

/** The app mark: a lock on a soft gradient tile. Decorative; the name sits beside it. */
export function Logo({ size = 44 }: { size?: number }) {
    return (
        <span className="logo" aria-hidden="true">
            <LockIcon size={Math.round(size * 0.5)} />
        </span>
    )
}
