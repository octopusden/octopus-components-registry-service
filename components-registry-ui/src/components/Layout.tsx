import { Link, useLocation } from 'react-router'
import { Package, History, Settings } from 'lucide-react'
import { cn } from '../lib/utils'

interface LayoutProps {
  children: React.ReactNode
}

const navItems = [
  { href: '/components', label: 'Components', icon: Package },
  { href: '/audit', label: 'Audit', icon: History },
  { href: '/admin', label: 'Admin', icon: Settings },
]

export function Layout({ children }: LayoutProps) {
  const location = useLocation()

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b bg-card sticky top-0 z-50">
        <div className="max-w-screen-xl mx-auto px-4 flex items-center h-14 gap-6">
          <span className="font-semibold text-foreground text-base tracking-tight mr-2">
            Components Registry
          </span>
          <nav className="flex items-center gap-1">
            {navItems.map(({ href, label, icon: Icon }) => {
              const isActive = location.pathname === href || location.pathname.startsWith(href + '/')
              return (
                <Link
                  key={href}
                  to={href}
                  className={cn(
                    'flex items-center gap-2 px-3 py-1.5 rounded-md text-sm font-medium transition-colors',
                    isActive
                      ? 'bg-accent text-accent-foreground'
                      : 'text-muted-foreground hover:text-foreground hover:bg-accent/50'
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {label}
                </Link>
              )
            })}
          </nav>
        </div>
      </header>
      <main className="max-w-screen-xl mx-auto px-4 py-6">{children}</main>
    </div>
  )
}
