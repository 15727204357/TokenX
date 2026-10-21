import { Navigate, Route, Routes } from 'react-router-dom';
import { ActivityPage } from './pages/ActivityPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { OrderPage } from './pages/OrderPage';
import { ProductsPage } from './pages/ProductsPage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/products" element={<ProductsPage />} />
      <Route path="/activities/:id" element={<ActivityPage />} />
      <Route path="/orders/:orderNo" element={<OrderPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
