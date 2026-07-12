import http from 'k6/http';
import { check, sleep } from 'k6';
import { login } from '../util/general-helper.js';

/* 현재 AI 측은 Mock을 적용해야 하기 때문(src 코드 수정)에 작성에 어려움이 있음 */