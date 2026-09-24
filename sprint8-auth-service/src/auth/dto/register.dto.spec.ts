import { validate } from 'class-validator';
import { RegisterDto } from './register.dto';

describe('RegisterDto validation', () => {
  function sample(overrides: Partial<RegisterDto> = {}): RegisterDto {
    return Object.assign(new RegisterDto(), {
      firstName: 'Priya',
      lastName: 'Menon',
      username: 'priya.menon',
      email: 'priya.menon@example.com',
      password: 'Capstone@2026',
      confirmPassword: 'Capstone@2026',
      ...overrides,
    });
  }

  it('accepts a fully valid payload', async () => {
    const dto = sample();
    const errors = await validate(dto);
    expect(errors).toHaveLength(0);
  });

  it('rejects a password shorter than 12 characters', async () => {
    const dto = sample({ password: 'Ab1!x', confirmPassword: 'Ab1!x' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
    expect(
      errors.flatMap((e) => Object.values(e.constraints ?? {})).join(' '),
    ).toContain('at least 12');
  });

  it('rejects a password lacking complexity (no special char)', async () => {
    const dto = sample({ password: 'Capstone2026ab', confirmPassword: 'Capstone2026ab' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
    expect(
      errors.flatMap((e) => Object.values(e.constraints ?? {})).join(' '),
    ).toContain('special character');
  });

  it('rejects an invalid email', async () => {
    const dto = sample({ email: 'not-an-email' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
  });

  it('rejects a username with spaces', async () => {
    const dto = sample({ username: 'bad username' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
  });
});