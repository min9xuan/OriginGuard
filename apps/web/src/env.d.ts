/// <reference types="vite/client" />

import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    adminLogin?: boolean
    adminOnly?: boolean
    investigatorOnly?: boolean
    fallback?: boolean
    permissions?: string[]
  }
}
