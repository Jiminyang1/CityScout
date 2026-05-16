export interface ApiResult<T> {
  success: boolean;
  errorMsg?: string;
  data?: T;
  total?: number;
}

export interface UserDTO {
  id: number;
  nickName: string;
  icon?: string;
}

export interface ShopType {
  id: number;
  name: string;
  icon?: string;
  sort?: number;
}

export interface Shop {
  id: number;
  name: string;
  typeId: number;
  images?: string;
  area?: string;
  address?: string;
  x?: number;
  y?: number;
  avgPrice?: number;
  sold?: number;
  comments?: number;
  score?: number;
  openHours?: string;
  distance?: number;
}

export interface Voucher {
  id: number;
  shopId: number;
  title: string;
  subTitle?: string;
  rules?: string;
  payValue: number;
  actualValue: number;
  type?: number;
  status?: number;
  stock?: number;
  beginTime?: string;
  endTime?: string;
}

export interface VoucherOrderStatus {
  id: string;
  voucherId?: number;
  voucherTitle?: string;
  status: number;
  statusText: string;
  createTime?: string;
  payTime?: string;
  useTime?: string;
  refundTime?: string;
  updateTime?: string;
}
