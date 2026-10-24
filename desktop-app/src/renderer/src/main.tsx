import { createRoot } from 'react-dom/client'
import './styles.css'
import { App } from './App'
import { OverlayApp } from './screens/Overlay'

const isOverlay = window.location.hash.startsWith('#/overlay')
document.documentElement.dataset.surface = isOverlay ? 'overlay' : 'app'

createRoot(document.getElementById('root')!).render(isOverlay ? <OverlayApp /> : <App />)
