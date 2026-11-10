// The guide on GitHub Pages: https://slashncode.github.io/posthog-ktor/. The docs workflow puts the
// API reference (Dokka) next to it, under /api/.
import starlight from '@astrojs/starlight'
import { defineConfig } from 'astro/config'

export default defineConfig({
  site: 'https://slashncode.github.io',
  base: '/posthog-ktor',
  integrations: [
    starlight({
      title: 'posthog-ktor',
      description: 'PostHog for Ktor servers: product analytics, feature flags, experiments and error tracking that know which request, person and session they belong to.',
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/slashncode/posthog-ktor' }],
      editLink: { baseUrl: 'https://github.com/slashncode/posthog-ktor/edit/main/docs/' },
      tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
      sidebar: [
        { label: 'Ktor', link: '/' },
        { label: 'API reference', link: '/api/' },
        { label: 'Changelog', link: 'https://github.com/slashncode/posthog-ktor/blob/main/CHANGELOG.md' },
      ],
    }),
  ],
})
