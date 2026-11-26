import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { LandingComponent } from './pages/landing/landing.component';
import { LoginComponent } from './pages/login/login.component';
import { RegisterComponent } from './pages/register/register.component';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { ChatComponent } from './pages/chat/chat.component';
import { DocumentsComponent } from './pages/documents/documents.component';

export const routes: Routes = [
  { path: '', component: LandingComponent },
  { path: 'login', component: LoginComponent },
  { path: 'register', component: RegisterComponent },
  { path: 'dashboard', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'workspaces/:id/chat', component: ChatComponent, canActivate: [authGuard] },
  { path: 'workspaces/:id/documents', component: DocumentsComponent, canActivate: [authGuard] },
  {
    path: 'workspaces/:id/documents/:documentId',
    // Lazy-loaded: TipTap + ProseMirror + Yjs are ~0.5 MB and only the
    // editor needs them, so they stay out of the initial bundle.
    loadComponent: () =>
      import('./pages/document-editor/document-editor.component').then(
        (module) => module.DocumentEditorComponent,
      ),
    canActivate: [authGuard],
  },
  { path: '**', redirectTo: '' },
];
