import fsd from '@feature-sliced/steiger-plugin'
import { defineConfig } from 'steiger'

export default defineConfig([
    ...fsd.configs.recommended,
    {
        // Generated/dependency noise is not part of the architecture.
        ignores: ['**/node_modules/**', '**/dist/**'],
    },
    {
        files: ['./src/shared/**'],
        rules: {
            // `shared` is a grab bag of leaf utilities; the public-API-per-slice
            // rule targets feature slices, not these.
            'fsd/public-api': 'off',
        },
    },
    {
        rules: {
            /**
             * Off deliberately, and it is a real trade-off rather than an
             * inconvenience silenced.
             *
             * The rule flags `send-message`, `create-link`, `delete-link` and
             * `chat-window` as premature: each has exactly one consumer today, and
             * "extract on the third repetition, not the second" says to inline them.
             * They are kept split because the decomposition was chosen up front and
             * the slices are where the next features land (an audio call reuses the
             * composer's shape; a second entry point reuses create-link).
             *
             * Everything that guards actual architecture — layer order, public-API
             * sidesteps, segmentless slices — stays on and must stay green, so this
             * config is not a way to make the linter quiet in general. Revisit if a
             * slice still has one consumer once the app grows.
             */
            'fsd/insignificant-slice': 'off',
        },
    },
])
