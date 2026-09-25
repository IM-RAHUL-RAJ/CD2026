import { Module } from '@nestjs/common';
import { LoginCryptoService } from './login-crypto.service';

@Module({
  providers: [LoginCryptoService],
  exports: [LoginCryptoService],
})
export class CryptoModule {}