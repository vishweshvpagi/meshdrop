import React from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from './context/ToastContext';
import { AppLayout } from './layouts/AppLayout/AppLayout';
import { DashboardPage } from './pages/Dashboard/DashboardPage';
import { NotFoundPage } from './pages/NotFound/NotFoundPage';
import { PeersPage } from './pages/Peers/PeersPage';
import { TransfersPage } from './pages/Transfers/TransfersPage';

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <ToastProvider>
        <Routes>
          <Route path="/" element={<AppLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="transfers" element={<TransfersPage />} />
            <Route path="peers" element={<PeersPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </ToastProvider>
    </BrowserRouter>
  );
};

export default App;
