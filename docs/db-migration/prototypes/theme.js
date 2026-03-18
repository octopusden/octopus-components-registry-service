// Shared theme logic for all prototype pages
const THEMES = {
    light: {
        '--bg': '#ffffff',
        '--fg': '#09090b',
        '--card': '#ffffff',
        '--border': '#e4e4e7',
        '--input': '#e4e4e7',
        '--ring': '#18181b',
        '--muted': '#f4f4f5',
        '--muted-fg': '#71717a',
        '--accent': '#f4f4f5',
        '--accent-fg': '#18181b',
        '--primary': '#18181b',
        '--primary-fg': '#fafafa',
        '--secondary': '#f4f4f5',
        '--secondary-fg': '#18181b',
        '--badge-green-bg': '#dcfce7',
        '--badge-green-fg': '#166534',
        '--badge-blue-bg': '#dbeafe',
        '--badge-blue-fg': '#1e40af',
        '--badge-yellow-bg': '#fef9c3',
        '--badge-yellow-fg': '#854d0e',
        '--badge-red-bg': '#fee2e2',
        '--badge-red-fg': '#991b1b',
        '--badge-outline-border': '#e4e4e7',
        '--badge-outline-fg': '#71717a',
        '--row-hover': '#f4f4f5',
        '--link': '#2563eb',
        '--diff-add-bg': '#dcfce7',
        '--diff-add-fg': '#166534',
        '--diff-rm-bg': '#fee2e2',
        '--diff-rm-fg': '#991b1b',
        '--override-dot': '#ca8a04',
        '--default-dot': '#a1a1aa',
    },
    dark: {
        '--bg': 'hsl(240 10% 3.9%)',
        '--fg': 'hsl(0 0% 98%)',
        '--card': 'hsl(240 10% 3.9%)',
        '--border': 'hsl(240 3.7% 15.9%)',
        '--input': 'hsl(240 3.7% 15.9%)',
        '--ring': 'hsl(240 4.9% 83.9%)',
        '--muted': 'hsl(240 3.7% 15.9%)',
        '--muted-fg': 'hsl(240 5% 64.9%)',
        '--accent': 'hsl(240 3.7% 15.9%)',
        '--accent-fg': 'hsl(0 0% 98%)',
        '--primary': 'hsl(0 0% 98%)',
        '--primary-fg': 'hsl(240 5.9% 10%)',
        '--secondary': 'hsl(240 3.7% 15.9%)',
        '--secondary-fg': 'hsl(0 0% 98%)',
        '--badge-green-bg': 'hsl(142 76% 16%)',
        '--badge-green-fg': 'hsl(142 71% 65%)',
        '--badge-blue-bg': 'hsl(217 91% 16%)',
        '--badge-blue-fg': 'hsl(217 91% 65%)',
        '--badge-yellow-bg': 'hsl(48 96% 14%)',
        '--badge-yellow-fg': 'hsl(48 96% 60%)',
        '--badge-red-bg': 'hsl(0 74% 16%)',
        '--badge-red-fg': 'hsl(0 74% 65%)',
        '--badge-outline-border': 'hsl(240 3.7% 15.9%)',
        '--badge-outline-fg': 'hsl(240 5% 64.9%)',
        '--row-hover': 'hsl(240 3.7% 10%)',
        '--link': 'hsl(217 91% 65%)',
        '--diff-add-bg': 'hsl(142 76% 8%)',
        '--diff-add-fg': 'hsl(142 71% 65%)',
        '--diff-rm-bg': 'hsl(0 74% 8%)',
        '--diff-rm-fg': 'hsl(0 74% 65%)',
        '--override-dot': 'hsl(48 96% 60%)',
        '--default-dot': 'hsl(240 5% 64.9%)',
    }
};

function getTheme() {
    return localStorage.getItem('cr-theme') || 'dark';
}

function setTheme(name) {
    const vars = THEMES[name];
    if (!vars) return;
    const root = document.documentElement;
    Object.entries(vars).forEach(([k, v]) => root.style.setProperty(k, v));
    localStorage.setItem('cr-theme', name);
    // Update toggle button icons
    document.querySelectorAll('.theme-icon-sun').forEach(el => el.style.display = name === 'dark' ? 'block' : 'none');
    document.querySelectorAll('.theme-icon-moon').forEach(el => el.style.display = name === 'light' ? 'block' : 'none');
}

function toggleTheme() {
    setTheme(getTheme() === 'dark' ? 'light' : 'dark');
}

// Apply saved theme on load
document.addEventListener('DOMContentLoaded', () => setTheme(getTheme()));
