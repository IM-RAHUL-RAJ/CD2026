import { Global, Injectable, Module, OnModuleDestroy } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Pool, PoolClient } from 'pg';

@Injectable()
export class DbService implements OnModuleDestroy {
  private readonly pool: Pool;

  constructor(config: ConfigService) {
    this.pool = new Pool({
      host: config.get<string>('database.host'),
      port: config.get<number>('database.port'),
      user: config.get<string>('database.user'),
      password: config.get<string>('database.password'),
      database: config.get<string>('database.name'),
      max: 10,
      idleTimeoutMillis: 30000,
    });
  }

  async query<T extends { [key: string]: unknown } = any>(
    text: string,
    params?: any[],
  ): Promise<{ rows: T[]; rowCount: number | null }> {
    return this.pool.query(text, params);
  }

  async getClient(): Promise<PoolClient> {
    return this.pool.connect();
  }

  async onModuleDestroy(): Promise<void> {
    await this.pool.end();
  }
}

@Global()
@Module({
  providers: [DbService],
  exports: [DbService],
})
export class DbModule {}