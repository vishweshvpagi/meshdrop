import React from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/Button/Button';
import { EmptyState } from '../../components/EmptyState/EmptyState';
import './NotFoundPage.css';

export const NotFoundPage: React.FC = () => {
  return (
    <div className="not-found-container">
      <EmptyState
        title="Page Not Found (404)"
        description="The requested page does not exist in MeshDrop."
        action={
          <Link to="/">
            <Button variant="primary">Return to Dashboard</Button>
          </Link>
        }
      />
    </div>
  );
};
