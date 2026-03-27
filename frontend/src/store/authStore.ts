import { create } from 'zustand';
import { User, AuthTokens } from '../types';

interface AuthStoreState {
  user: User | null;
  tokens: AuthTokens | null;
  setUser: (user: User | null) => void;
  setTokens: (tokens: AuthTokens) => void;
  setAuth: (user: User, tokens: AuthTokens) => void;
  logout: () => void;
  isAuthenticated: () => boolean;
}

export const useAuthStore = create<AuthStoreState>((set, get) => ({
  user: localStorage.getItem('user') ? JSON.parse(localStorage.getItem('user')!) : null,
  tokens: localStorage.getItem('tokens') ? JSON.parse(localStorage.getItem('tokens')!) : null,

  setUser: (user) => {
    set({ user });
    if (user) {
      localStorage.setItem('user', JSON.stringify(user));
    } else {
      localStorage.removeItem('user');
    }
  },

  setTokens: (tokens) => {
    set({ tokens });
    localStorage.setItem('tokens', JSON.stringify(tokens));
  },

  setAuth: (user, tokens) => {
    set({ user, tokens });
    localStorage.setItem('user', JSON.stringify(user));
    localStorage.setItem('tokens', JSON.stringify(tokens));
  },

  logout: () => {
    set({ user: null, tokens: null });
    localStorage.removeItem('user');
    localStorage.removeItem('tokens');
  },

  isAuthenticated: () => {
    const { tokens } = get();
    return !!tokens?.accessToken;
  },
}));
