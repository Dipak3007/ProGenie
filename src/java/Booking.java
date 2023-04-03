/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/JSP_Servlet/Servlet.java to edit this template
 */

import java.io.*;
import java.sql.*;
import javax.servlet.*;
import javax.servlet.http.*;

public class Booking extends HttpServlet {
@Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String gname=request.getParameter("name");
        String service=request.getParameter("service");
        String doa=request.getParameter("doa");
        String cname=request.getParameter("cname");
        String phone=request.getParameter("call");
        String email=request.getParameter("email");
        String add=request.getParameter("add");
        RequestDispatcher dis=null;
        Connection con=null;

        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            con=DriverManager.getConnection("jdbc:mysql://localhost:3306/progenie?useSSL=false","root", "root");
            PreparedStatement ps=con.prepareStatement("insert into booking(gname,service,appointment,cname,phone,email,address) values (?,?,?,?,?,?,?)");
            ps.setString(1, gname);
            ps.setString(2, service);
            ps.setString(3, doa);
            ps.setString(4, cname);
            ps.setString(5, phone);
            ps.setString(6, email);
            ps.setString(7, add);
            int row=ps.executeUpdate();
            dis=request.getRequestDispatcher("electrician.jsp");
            if(row>0)
                request.setAttribute("status", "success");
            else
                request.setAttribute("status", "failed");
        }
        catch(Exception e){
            e.printStackTrace();
        }
        finally{
            try{
                con.close();     
                dis.forward(request, response);
            }
            catch(Exception e){
                e.printStackTrace();
            }
        }
    }
}
